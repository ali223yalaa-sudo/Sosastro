/**
 * Arrow-mode matchmaker — parallel to Matchmaker.ts (1v1). Uses GAME_ROOM_ARROW / /room4.
 * Never imports or calls the 1v1 Matchmaker / GameRoom.
 */
import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";
import {
  prepareMatchWithTickets,
  rejectTicket,
  type TicketWaiting,
} from "./matchTicketsShared";
import { buildRoomWebsocketUrl } from "./seatAuth";

const SEATS = 4;
/** Prefer real opponents; fill remaining seats with bots after this solo/partial wait. */
const BOT_AFTER_MS = 12_000;

const ARABIC_BOT_NAMES = [
  "أحمد",
  "محمد",
  "يوسف",
  "خالد",
  "عمر",
  "سارة",
  "نورة",
  "فاطمة",
  "ليلى",
  "حسن",
  "علي",
  "مريم",
  "عبدالله",
  "ريم",
  "طارق",
  "هدى",
  "زياد",
  "لينا",
  "فارس",
  "دانة",
  "ماجد",
  "هند",
  "سامي",
  "جنى",
];

type Waiting = TicketWaiting;

const BOT_PIECE_STYLES = ["disc", "football", "basketball", "egg", "snake"];

function sanitizePieceStyleId(raw: string | null): string {
  const id = (raw ?? "disc").trim().slice(0, 32);
  return BOT_PIECE_STYLES.includes(id) ? id : "disc";
}

function pickBotPieceStyle(): string {
  return BOT_PIECE_STYLES[Math.floor(Math.random() * BOT_PIECE_STYLES.length)] ?? "disc";
}


const BOT_DICE_STYLES = [
  "cdr_classic",
  "cdr_classic_neon",
  "cdr_nested",
  "cdr_nested_lag",
  "cdr_nested_shiny",
  "cdr_nested_crystal",
  "cdr_wire",
  "cdr_ruby",
  "cdr_gold",
  "cdr_midnight",
];

function sanitizeDiceStyleId(raw: string | null): string {
  const id = (raw ?? "cdr_classic").trim().slice(0, 40);
  return BOT_DICE_STYLES.includes(id) ? id : "cdr_classic";
}

function pickBotDiceStyle(): string {
  return BOT_DICE_STYLES[Math.floor(Math.random() * BOT_DICE_STYLES.length)] ?? "cdr_classic";
}


function pickArabicBotName(): string {
  const i = Math.floor(Math.random() * ARABIC_BOT_NAMES.length);
  return ARABIC_BOT_NAMES[i] ?? "أحمد";
}

function botPlayerId(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

type BootPlayer = {
  playerId: string;
  name: string;
  isBot?: boolean;
  pieceStyleId?: string;
  diceStyleId?: string;
};

export class MatchmakerArrow extends DurableObject<Env> {
  private queue: Waiting[] = [];
  private lastHost = "amethyst-ludo-dice.www-hmzhh123-com.workers.dev";

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }

    const url = new URL(request.url);
    this.lastHost = url.host;
    const playerId = url.searchParams.get("playerId") || crypto.randomUUID();
    const name = url.searchParams.get("name") || "Player";
    const pieceStyleId = sanitizePieceStyleId(url.searchParams.get("pieceStyleId"));
    const diceStyleId = sanitizeDiceStyleId(url.searchParams.get("diceStyleId"));
    const ticketId = (url.searchParams.get("ticketId") || "").trim();

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (!ticketId) {
      rejectTicket(server, "missing_ticket");
      return new Response(null, { status: 101, webSocket: client });
    }

    const waiting: Waiting = {
      playerId,
      name,
      pieceStyleId,
      diceStyleId,
      ticketId,
      socket: server,
      joinedAt: Date.now(),
    };
    this.queue = this.queue.filter((w) => w.socket.readyState === WebSocket.OPEN);
    this.queue.push(waiting);

    server.addEventListener("close", () => {
      this.queue = this.queue.filter((w) => w.socket !== server);
      void this.scheduleAlarm();
    });

    server.send(
      JSON.stringify({
        type: "QueueJoined",
        payload: { playerId, waiting: this.queue.length, seats: SEATS },
      }),
    );

    if (this.queue.length >= SEATS) {
      await this.pairFullLobby();
    } else {
      await this.scheduleAlarm();
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm(): Promise<void> {
    this.queue = this.queue.filter((w) => w.socket.readyState === WebSocket.OPEN);

    if (this.queue.length >= SEATS) {
      await this.pairFullLobby();
      await this.scheduleAlarm();
      return;
    }

    if (this.queue.length > 0) {
      const oldest = Math.min(...this.queue.map((w) => w.joinedAt));
      if (Date.now() - oldest >= BOT_AFTER_MS) {
        await this.fillWithBotsAndStart();
      }
    }

    await this.scheduleAlarm();
  }

  private async scheduleAlarm(): Promise<void> {
    this.queue = this.queue.filter((w) => w.socket.readyState === WebSocket.OPEN);
    if (this.queue.length === 0) {
      try {
        await this.ctx.storage.deleteAlarm();
      } catch {
        // ignore
      }
      return;
    }
    if (this.queue.length >= SEATS) {
      await this.ctx.storage.setAlarm(Date.now() + 100);
      return;
    }
    const oldest = Math.min(...this.queue.map((w) => w.joinedAt));
    const due = oldest + BOT_AFTER_MS;
    await this.ctx.storage.setAlarm(Math.max(due, Date.now() + 50));
  }

  private async pairFullLobby(): Promise<void> {
    while (this.queue.length >= SEATS) {
      const humans: Waiting[] = [];
      let ok = true;
      for (let i = 0; i < SEATS; i += 1) {
        const w = this.queue.shift()!;
        if (w.socket.readyState !== WebSocket.OPEN) {
          ok = false;
          if (w.socket.readyState === WebSocket.OPEN) humans.push(w);
          break;
        }
        humans.push(w);
      }
      if (!ok || humans.length < SEATS) {
        for (const h of humans) {
          if (h.socket.readyState === WebSocket.OPEN) this.queue.unshift(h);
        }
        break;
      }
      const players: BootPlayer[] = humans.map((h) => ({
        playerId: h.playerId,
        name: h.name,
        pieceStyleId: h.pieceStyleId,
        diceStyleId: h.diceStyleId,
      }));
      const prepared = await prepareMatchWithTickets(this.env, "arrow_4p", humans);
      if ("error" in prepared) {
        for (const h of humans) rejectTicket(h.socket, prepared.error);
        continue;
      }
      const matchId = prepared.matchId;
      await this.bootRoom(matchId, players);
      for (const h of humans) this.assignMatch(h, matchId, players, prepared.seatTokens[h.playerId]!);
    }
  }

  private async fillWithBotsAndStart(): Promise<void> {
    this.queue = this.queue.filter((w) => w.socket.readyState === WebSocket.OPEN);
    if (this.queue.length === 0 || this.queue.length >= SEATS) return;

    const humans = [...this.queue];
    this.queue = [];
    const players: BootPlayer[] = humans.map((h) => ({
      playerId: h.playerId,
      name: h.name,
      pieceStyleId: h.pieceStyleId,
        diceStyleId: h.diceStyleId,
    }));
    while (players.length < SEATS) {
      players.push({
        playerId: botPlayerId(),
        name: pickArabicBotName(),
        isBot: true,
        pieceStyleId: pickBotPieceStyle(),
        diceStyleId: pickBotDiceStyle(),
      });
    }
    const prepared = await prepareMatchWithTickets(this.env, "arrow_4p", humans);
    if ("error" in prepared) {
      for (const h of humans) rejectTicket(h.socket, prepared.error);
      return;
    }
    const matchId = prepared.matchId;
    await this.bootRoom(matchId, players);
    for (const h of humans) this.assignMatch(h, matchId, players, prepared.seatTokens[h.playerId]!);
  }

  private async bootRoom(matchId: string, players: BootPlayer[]): Promise<void> {
    const id = this.env.GAME_ROOM_ARROW.idFromName(matchId);
    const stub = this.env.GAME_ROOM_ARROW.get(id);
    await stub.fetch("https://room/boot", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ matchId, players }),
    });
  }

  private assignMatch(w: Waiting, matchId: string, players: BootPlayer[], seatToken: string): void {
    try {
      w.socket.send(
        JSON.stringify({
          type: "MatchFound",
          payload: {
            matchId,
            playerId: w.playerId,
            websocketUrl: buildRoomWebsocketUrl({
              host: this.lastHost,
              path: `/roomArrow/${matchId}`,
              playerId: w.playerId,
              seatToken,
            }),
            players,
            mode: "arrow4",
          },
        }),
      );
      w.socket.close(1000, "matched");
    } catch {
      // socket already closed
    }
  }
}
