import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";
import {
  prepareMatchWithTickets,
  rejectTicket,
  type TicketWaiting,
} from "./matchTicketsShared";
import { buildRoomWebsocketUrl } from "./seatAuth";

/** Prefer a real opponent; only spawn a bot after this solo wait. */
const BOT_AFTER_MS = 10_000;

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

/** 64-char hex so clients that expect a Nostr pubkey never crash. */
function botPlayerId(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export class Matchmaker extends DurableObject<Env> {
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
      void this.scheduleBotAlarm();
    });

    server.send(
      JSON.stringify({
        type: "QueueJoined",
        payload: { playerId, waiting: this.queue.length },
      }),
    );

    // Real humans always win over bots when two are waiting.
    if (this.queue.length >= 2) {
      await this.pairHumans(request);
    } else {
      await this.scheduleBotAlarm();
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm(): Promise<void> {
    this.queue = this.queue.filter((w) => w.socket.readyState === WebSocket.OPEN);

    if (this.queue.length >= 2) {
      // Synthetic request URL for host — use last seen host.
      await this.pairHumansFromQueue();
      await this.scheduleBotAlarm();
      return;
    }

    const alone = this.queue[0];
    if (alone && Date.now() - alone.joinedAt >= BOT_AFTER_MS) {
      await this.pairWithBot(alone);
    }

    await this.scheduleBotAlarm();
  }

  private async scheduleBotAlarm(): Promise<void> {
    this.queue = this.queue.filter((w) => w.socket.readyState === WebSocket.OPEN);
    if (this.queue.length === 0) {
      try {
        await this.ctx.storage.deleteAlarm();
      } catch {
        // ignore
      }
      return;
    }
    if (this.queue.length >= 2) {
      // Humans should pair on next message; still nudge quickly.
      await this.ctx.storage.setAlarm(Date.now() + 100);
      return;
    }
    const alone = this.queue[0]!;
    const due = alone.joinedAt + BOT_AFTER_MS;
    await this.ctx.storage.setAlarm(Math.max(due, Date.now() + 50));
  }

  private async pairHumans(request: Request): Promise<void> {
    this.lastHost = new URL(request.url).host;
    await this.pairHumansFromQueue();
  }

  private async pairHumansFromQueue(): Promise<void> {
    while (this.queue.length >= 2) {
      const a = this.queue.shift()!;
      const b = this.queue.shift()!;
      if (
        a.socket.readyState !== WebSocket.OPEN ||
        b.socket.readyState !== WebSocket.OPEN
      ) {
        if (a.socket.readyState === WebSocket.OPEN) this.queue.unshift(a);
        if (b.socket.readyState === WebSocket.OPEN) this.queue.unshift(b);
        break;
      }
      const prepared = await prepareMatchWithTickets(this.env, "classic_1v1", [a, b]);
      if ("error" in prepared) {
        rejectTicket(a.socket, prepared.error);
        rejectTicket(b.socket, prepared.error);
        continue;
      }
      const matchId = prepared.matchId;
      const players = [
        { playerId: a.playerId, name: a.name, pieceStyleId: a.pieceStyleId, diceStyleId: a.diceStyleId },
        { playerId: b.playerId, name: b.name, pieceStyleId: b.pieceStyleId, diceStyleId: b.diceStyleId },
      ];
      await this.bootRoom(matchId, players);
      this.assignMatch(a, matchId, players, prepared.seatTokens[a.playerId]!);
      this.assignMatch(b, matchId, players, prepared.seatTokens[b.playerId]!);
    }
  }

  private async pairWithBot(human: Waiting): Promise<void> {
    if (human.socket.readyState !== WebSocket.OPEN) return;
    // Remove from queue first so a late second human doesn't double-match.
    this.queue = this.queue.filter((w) => w.socket !== human.socket);
    const botId = botPlayerId();
    const botName = pickArabicBotName();
    const prepared = await prepareMatchWithTickets(this.env, "classic_1v1", [human]);
    if ("error" in prepared) {
      rejectTicket(human.socket, prepared.error);
      return;
    }
    const matchId = prepared.matchId;
    const players = [
      {
        playerId: human.playerId,
        name: human.name,
        pieceStyleId: human.pieceStyleId,
        diceStyleId: human.diceStyleId,
      },
      {
        playerId: botId,
        name: botName,
        isBot: true as const,
        pieceStyleId: pickBotPieceStyle(),
        diceStyleId: pickBotDiceStyle(),
      },
    ];
    await this.bootRoom(matchId, players);
    this.assignMatch(human, matchId, players, prepared.seatTokens[human.playerId]!);
  }

  private async bootRoom(
    matchId: string,
    players: Array<{ playerId: string; name: string; isBot?: boolean; pieceStyleId?: string; diceStyleId?: string }>,
  ): Promise<void> {
    const id = this.env.GAME_ROOM.idFromName(matchId);
    const stub = this.env.GAME_ROOM.get(id);
    await stub.fetch("https://room/boot", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ matchId, players }),
    });
  }

  private assignMatch(
    w: Waiting,
    matchId: string,
    players: Array<{ playerId: string; name: string; isBot?: boolean; pieceStyleId?: string; diceStyleId?: string }>,
    seatToken: string,
  ): void {
    try {
      w.socket.send(
        JSON.stringify({
          type: "MatchFound",
          payload: {
            matchId,
            playerId: w.playerId,
            websocketUrl: buildRoomWebsocketUrl({
              host: this.lastHost,
              path: `/room/${matchId}`,
              playerId: w.playerId,
              seatToken,
            }),
            players,
          },
        }),
      );
      w.socket.close(1000, "matched");
    } catch {
      // socket already closed
    }
  }
}
