import { DurableObject } from "cloudflare:workers";
import {
  absoluteTrackIndex,
  applyMove,
  createPawns,
  createPlayersFromList,
  winner,
  type Color,
  type Pawn,
  type Player,
} from "./engineArrow";
import { ARROW_TRACK_JUMPS, HOME_ENTRY_FROM_STEP, HOME_ENTRY_TO_STEP } from "./arrowRules";
import { fairDie, secureUint32 } from "./fairDie";
import type { Env } from "./index";
import { settleMatchEconomy } from "./settleEconomy";
import { awardMoveProgressXp } from "./progressBridge";
import { assertRoomSeat } from "./seatAuth";
import { consumePlayedFace, allLegalPawnIds, facesForPiece, pickAutoPair, pruneUnusableFaces } from "./moveQueueArrow";

/** Human must roll within this window or sticky autoplay starts. */
const ROLL_TIMEOUT_MS = 18_000;
/** Human must pick a piece within this window or sticky autoplay starts. */
const MOVE_TIMEOUT_MS = 18_000;
/** Continuous disconnect longer than this → opponent wins. */
const ABSENT_WIN_MS = 60_000;

/**
 * Sticky auto must still look like a normal turn: skip only the human wait,
 * not the dice/move animations. Timings match Android LudoLiveConfig.
 */
const CLIENT_ROLL_ANIM_MS = 500;
const CLIENT_STEP_MS = 88;
/** Pause after a six before the next roll (human grace + auto pace). */
/** Auto-only beat after a six so the face is readable; humans re-roll immediately. */
const SIX_REROLL_GAP_MS = 900;
/** Auto wait after TurnStarted before rolling — calm, readable. */
const AUTO_PRE_ROLL_MS = 2_600;
/** How long to wait for the human client to ack TurnStarted before re-sending. */
const TURN_ACK_WAIT_MS = 4_000;
/** Extra time after a dice face lands so humans/auto can read it. */
const AUTO_VIEW_RESULT_MS = 1_700;
/** Extra client time to walk a captured pawn home (not a snap). */
const CLIENT_CAPTURE_RETURN_MS = 650;
/** Buffer after walk anim before the next seat's roll timer starts. */
const TURN_HANDOFF_BUFFER_MS = 400;

function stepForAbsolute(color: Color, abs: number): number | null {
  for (let step = 0; step <= 50; step += 1) {
    if (absoluteTrackIndex(color, step) === abs) return step;
  }
  return null;
}
/** Auto pause after move opens before picking a piece. */
const AUTO_PICK_PAUSE_MS = 1_500;

/** Max wait for both clients to finish the VS intro before forcing start. */
const INTRO_FORCE_MS = 12_000;

type Seat = {
  playerId: string;
  name: string;
  color: Color;
  socket: WebSocket | null;
  disconnectedAt: number | null;
  /**
   * Sticky autoplay: after the first human timeout the server plays this seat
   * for the rest of the match until ResumeManual (checkmark / intentional tap).
   */
  autoPlay: boolean;
  /** Client finished the local VS countdown and is ready for TurnStarted. */
  introDone: boolean;
  /** Server-driven seat — no WebSocket, always auto-paced. */
  isBot: boolean;
  /** Cosmetic token style id (disc / football / …). */
  pieceStyleId: string;
  /** Cosmetic dice skin id from store. */
  diceStyleId: string;
  /** Forfeit / long absence — skipped in turn order. */
  eliminated: boolean;
};

/** Max consecutive sixes before the turn is burnt (classic Ludo). */
const MAX_CHAIN_SIXES = 3;

type Phase = "ROLL" | "MOVE" | "FINISHED";

type RoomState = {
  matchId: string;
  seats: Seat[];
  pawns: Pawn[];
  players: Player[];
  currentIndex: number;
  phase: Phase;
  dice: number;
  /** Rolls in the current turn chain (e.g. [6, 5]) — display + move source. */
  rollChain: number[];
  /** Remaining faces to play as moves after the roll chain ends (FIFO). */
  moveQueue: number[];
  turnId: string;
  sequence: number;
  winner: Color | null;
  started: boolean;
  deadlineAt: number | null;
  /**
   * When true, the deadline only finishes waiting for client move/roll anim
   * then emits TurnStarted — it must NOT force the next seat into autoplay.
   */
  pendingTurnStart: boolean;
  /** After a move anim, resolve the next face in moveQueue (same turn). */
  pendingNextFace: boolean;
  /** Force-start the match if intros stall. */
  introDeadlineAt: number | null;
  /** True until the current human seat acks TurnStarted (roll timer starts then). */
  turnAwaitingAck: boolean;
  /** While awaiting ack: re-send TurnStarted / failsafe arm timer. */
  turnAckDeadlineAt: number | null;
  /** How many times we re-announced TurnStarted while waiting for ack. */
  turnAckResends: number;
};

export class GameRoomArrow extends DurableObject<Env> {
  private state: RoomState | null = null;

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname === "/boot") {
      const body = (await request.json()) as {
        matchId: string;
        players: Array<{ playerId: string; name: string; isBot?: boolean; pieceStyleId?: string; diceStyleId?: string }>;
      };
      if (!this.state) {
        const players = createPlayersFromList(body.players);
        this.state = {
          matchId: body.matchId,
          seats: players.map((p) => ({
            playerId: p.playerId,
            name: p.name,
            color: p.color,
            socket: null,
            disconnectedAt: null,
            autoPlay: !!p.isBot,
            introDone: !!p.isBot,
            isBot: !!p.isBot,
            pieceStyleId: p.pieceStyleId || "disc",
            diceStyleId: p.diceStyleId || "cdr_classic",
            eliminated: false,
          })),
          pawns: createPawns(players),
          players,
          currentIndex: 0,
          phase: "ROLL",
          dice: 0,
          rollChain: [],
          moveQueue: [],
          turnId: crypto.randomUUID(),
          sequence: 0,
          winner: null,
          started: false,
          deadlineAt: null,
          pendingTurnStart: false,
          pendingNextFace: false,
          introDeadlineAt: null,
          turnAwaitingAck: false,
          turnAckDeadlineAt: null,
          turnAckResends: 0,
        };
      }
      return Response.json({ ok: true, matchId: body.matchId });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }

    const playerId = url.searchParams.get("playerId") ?? "";
    const seatToken = url.searchParams.get("seatToken") ?? "";
    if (!this.state || !this.state.seats.some((s) => s.playerId === playerId)) {
      return new Response("Unknown player", { status: 403 });
    }
    const denied = await assertRoomSeat(this.env, {
      matchId: this.state.matchId,
      playerId,
      seatToken,
    });
    if (denied) return denied;

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    const seat = this.state.seats.find((s) => s.playerId === playerId)!;
    if (seat.autoPlay === undefined) seat.autoPlay = false;
    if (seat.introDone === undefined) seat.introDone = false;
    if (seat.isBot === undefined) seat.isBot = !!this.state.players.find((p) => p.playerId === playerId)?.isBot;
    if (this.state.pendingTurnStart === undefined) this.state.pendingTurnStart = false;
    if (this.state.pendingNextFace === undefined) this.state.pendingNextFace = false;
    if (this.state.turnAwaitingAck === undefined) this.state.turnAwaitingAck = false;
    if (this.state.turnAckDeadlineAt === undefined) this.state.turnAckDeadlineAt = null;
    if (this.state.turnAckResends === undefined) this.state.turnAckResends = 0;
    if (this.state.moveQueue === undefined) this.state.moveQueue = [];
    if (this.state.rollChain === undefined) this.state.rollChain = [];
    if (seat.isBot) {
      return new Response("Bot seat", { status: 403 });
    }
    const wasAway = seat.disconnectedAt != null || seat.socket == null;
    seat.socket?.close(1000, "replaced");
    seat.socket = server;
    seat.disconnectedAt = null;

    server.addEventListener("message", (event) => {
      void this.onMessage(playerId, String(event.data));
    });
    server.addEventListener("close", () => {
      if (seat.socket === server) {
        seat.socket = null;
        // Finished matches (win / forfeit) must not start the absent timer.
        if (this.state?.phase === "FINISHED") return;
        seat.disconnectedAt = Date.now();
        this.broadcast({
          type: "PlayerAway",
          sequence: ++this.state!.sequence,
          payload: { playerId, since: seat.disconnectedAt },
        });
        void this.scheduleWake();
      }
    });

    this.send(server, {
      type: "Snapshot",
      sequence: this.state.sequence,
      payload: this.snapshotPayload(playerId),
    });

    if (this.state.started && wasAway) {
      this.broadcast({
        type: "PlayerPresent",
        sequence: ++this.state.sequence,
        payload: { playerId, showCheck: false },
      });
    }

    // Humans connected (bots need no socket) → arm intro deadline; start after VS intros.
    if (!this.state.started && this.humansConnected()) {
      if (this.state.introDeadlineAt == null) {
        this.state.introDeadlineAt = Date.now() + INTRO_FORCE_MS;
      }
      this.tryStartMatch();
    }

    void this.scheduleWake();
    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm(): Promise<void> {
    await this.processDeadlines();
  }

  private async onMessage(playerId: string, raw: string) {
    if (!this.state || this.state.phase === "FINISHED") return;
    let msg: { type?: string; pieceId?: string; dice?: number };
    try {
      msg = JSON.parse(raw) as { type?: string; pieceId?: string; dice?: number };
    } catch {
      return;
    }

    if (msg.type === "Here" || msg.type === "Heartbeat") {
      const seat = this.state.seats.find((s) => s.playerId === playerId);
      if (!seat) return;
      const wasAway = seat.disconnectedAt != null;
      seat.disconnectedAt = null;
      if (wasAway && this.state.started) {
        this.broadcast({
          type: "PlayerPresent",
          sequence: ++this.state.sequence,
          payload: { playerId, showCheck: false },
        });
      }
      // Heartbeats must flush overdue anim gates / timeouts (alarms can be late).
      await this.processDeadlines();
      // If this human owns the turn but never got / missed TurnStarted, re-announce.
      this.maybeResendTurnTo(playerId);
      return;
    }

    if (msg.type === "TurnAck") {
      const seat = this.state.seats[this.state.currentIndex];
      if (!seat || seat.playerId !== playerId) return;
      if (!this.state.turnAwaitingAck) return;
      this.armRollDeadlineAfterAck(seat);
      await this.scheduleWake();
      return;
    }

    // VS intro finished on this client — start when both are ready (or force).
    if (msg.type === "IntroDone") {
      const seat = this.state.seats.find((s) => s.playerId === playerId);
      if (!seat) return;
      seat.introDone = true;
      this.tryStartMatch();
      await this.scheduleWake();
      return;
    }

    if (msg.type === "ResumeManual") {
      const seat = this.state.seats.find((s) => s.playerId === playerId);
      if (!seat || !seat.autoPlay || seat.isBot) return;
      seat.autoPlay = false;
      const current = this.state.seats[this.state.currentIndex];
      // Only re-arm a human timer if we are waiting on their action (not anim gap).
      if (
        current?.playerId === playerId &&
        !this.state.pendingTurnStart &&
        !this.state.pendingNextFace
      ) {
        const wait = this.state.phase === "MOVE" ? MOVE_TIMEOUT_MS : ROLL_TIMEOUT_MS;
        this.state.deadlineAt = Date.now() + wait;
      }
      this.broadcast({
        type: "AutoMode",
        sequence: ++this.state.sequence,
        payload: {
          playerId,
          active: false,
          deadlineAt:
            current?.playerId === playerId &&
            !this.state.pendingTurnStart &&
            !this.state.pendingNextFace
              ? this.state.deadlineAt
              : null,
        },
      });
      await this.scheduleWake();
      return;
    }

    // Voluntary exit — eliminate this seat; sole survivor wins.
    if (msg.type === "Forfeit") {
      if (!this.state.started) return;
      await this.eliminateSeat(playerId, "forfeit");
      await this.scheduleWake();
      return;
    }

    // Match social (chat / emoji) — allowed anytime for either player, no turn gate.
    if (msg.type === "Chat") {
      const seat = this.state.seats.find((s) => s.playerId === playerId);
      if (!seat || !this.state.started) return;
      const text = String((msg as { text?: string }).text ?? "")
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, 200);
      if (!text) return;
      this.broadcast({
        type: "Chat",
        sequence: ++this.state.sequence,
        payload: { playerId, text, at: Date.now() },
      });
      return;
    }

    if (msg.type === "Emoji") {
      const seat = this.state.seats.find((s) => s.playerId === playerId);
      if (!seat || !this.state.started) return;
      const emoji = String((msg as { emoji?: string }).emoji ?? "")
        .trim()
        .slice(0, 8);
      if (!emoji) return;
      this.broadcast({
        type: "Emoji",
        sequence: ++this.state.sequence,
        payload: { playerId, emoji, at: Date.now() },
      });
      return;
    }

    const seat = this.state.seats[this.state.currentIndex];
    if (!seat || seat.playerId !== playerId) {
      this.reject(playerId, "NotYourTurn");
      return;
    }

    // Flush overdue anim gates before accepting input (missed alarms).
    if (
      (this.state.pendingTurnStart || this.state.pendingNextFace) &&
      this.state.deadlineAt != null &&
      Date.now() >= this.state.deadlineAt
    ) {
      await this.processDeadlines();
    }

    // Human intentional action leaves sticky auto (bots stay auto).
    if (seat.autoPlay && !seat.isBot && (msg.type === "RollDice" || msg.type === "MovePiece")) {
      seat.autoPlay = false;
      this.broadcast({
        type: "AutoMode",
        sequence: ++this.state.sequence,
        payload: { playerId, active: false, deadlineAt: this.state.deadlineAt },
      });
    }

    if (seat.autoPlay) {
      this.reject(playerId, "AutoModeActive");
      return;
    }

    if (this.state.pendingTurnStart || this.state.pendingNextFace) {
      this.reject(playerId, "Busy");
      return;
    }

    if (msg.type === "RollDice") {
      if (this.state.phase !== "ROLL") {
        this.reject(playerId, "WrongPhase");
        return;
      }
      // Rolling implies the client has the turn UI — treat as ack.
      if (this.state.turnAwaitingAck) {
        this.state.turnAwaitingAck = false;
        this.state.turnAckDeadlineAt = null;
        this.state.turnAckResends = 0;
      }
      // Human is actively playing — refresh the timeout so hiccups don't steal the turn.
      this.state.deadlineAt = Date.now() + ROLL_TIMEOUT_MS;
      await this.performRoll(seat, false);
      return;
    }

    if (msg.type === "MovePiece") {
      if (this.state.phase !== "MOVE") {
        this.reject(playerId, "WrongPhase");
        return;
      }
      this.state.deadlineAt = Date.now() + MOVE_TIMEOUT_MS;
      const face = typeof msg.dice === "number" ? msg.dice : this.state.dice;
      await this.performMove(seat, msg.pieceId ?? "", face, false);
      return;
    }

    // Announce which legal piece the human is about to move (opponent preview).
    if (msg.type === "PieceSelected") {
      if (this.state.phase !== "MOVE") return;
      const pieceId = String(msg.pieceId ?? "");
      const faces = facesForPiece(this.state.pawns, seat.color, this.state.moveQueue, pieceId);
      if (faces.length === 0) return;
      this.broadcast({
        type: "PieceSelected",
        sequence: ++this.state.sequence,
        payload: {
          playerId: seat.playerId,
          pieceId,
          legalPawnIds: allLegalPawnIds(this.state.pawns, seat.color, this.state.moveQueue),
          faces,
        },
      });
    }
  }

  private async processDeadlines() {
    if (!this.state || this.state.phase === "FINISHED") return;
    const now = Date.now();

    if (!this.state.started) {
      if (this.state.introDeadlineAt != null && now >= this.state.introDeadlineAt) {
        this.tryStartMatch();
      }
      await this.scheduleWake();
      return;
    }

    for (const seat of this.state.seats) {
      if (seat.isBot || seat.eliminated) continue;
      if (seat.disconnectedAt != null && now - seat.disconnectedAt >= ABSENT_WIN_MS) {
        await this.eliminateSeat(seat.playerId, "opponent_absent");
        await this.scheduleWake();
        return;
      }
    }

    if (this.state.deadlineAt == null || now < this.state.deadlineAt) {
      // Still waiting on TurnAck failsafe clock?
      if (
        this.state.turnAwaitingAck &&
        this.state.turnAckDeadlineAt != null &&
        now >= this.state.turnAckDeadlineAt
      ) {
        await this.onTurnAckTimeout();
      }
      await this.scheduleWake();
      return;
    }

    // Animation gap finished → announce the next turn (never force auto here).
    if (this.state.pendingTurnStart) {
      this.state.pendingTurnStart = false;
      this.emitTurnStarted();
      await this.scheduleWake();
      return;
    }

    // TurnAck wait expired while deadlineAt was the ack clock.
    if (this.state.turnAwaitingAck) {
      await this.onTurnAckTimeout();
      await this.scheduleWake();
      return;
    }

    // Same turn: reopen move choice after walk anim (player picks face, not FIFO).
    if (this.state.pendingNextFace) {
      this.state.pendingNextFace = false;
      const seat = this.state.seats[this.state.currentIndex];
      if (seat) {
        this.openMoveChoice(seat, seat.autoPlay || seat.isBot, /* animateRoll */ false);
      }
      await this.scheduleWake();
      return;
    }

    const seat = this.state.seats[this.state.currentIndex];
    if (!seat) return;

    // Human timeout → enter sticky auto. Already-auto seats just continue paced play.
    if (!seat.autoPlay) {
      this.enterAutoPlay(seat);
    }

    if (this.state.phase === "ROLL") {
      await this.performRoll(seat, true);
    } else if (this.state.phase === "MOVE") {
      const pick = pickAutoPair(this.state.pawns, seat.color, this.state.moveQueue);
      if (pick) {
        this.broadcast({
          type: "PieceSelected",
          sequence: ++this.state.sequence,
          payload: {
            playerId: seat.playerId,
            pieceId: pick.pieceId,
            legalPawnIds: allLegalPawnIds(this.state.pawns, seat.color, this.state.moveQueue),
            faces: [pick.face],
          },
        });
        await this.performMove(seat, pick.pieceId, pick.face, true);
      } else {
        this.state.moveQueue = [];
        this.openMoveChoice(seat, true, false);
      }
    }

    await this.scheduleWake();
  }

  private humansConnected(): boolean {
    if (!this.state) return false;
    return this.state.seats.filter((s) => !s.isBot).every((s) => s.socket != null);
  }

  private tryStartMatch() {
    if (!this.state || this.state.started) return;
    if (!this.humansConnected()) return;
    const now = Date.now();
    const introsDone = this.state.seats.every((s) => s.introDone || s.isBot);
    const forced =
      this.state.introDeadlineAt != null && now >= this.state.introDeadlineAt;
    if (!introsDone && !forced) return;

    this.state.started = true;
    this.state.introDeadlineAt = null;
    this.broadcast({
      type: "MatchReady",
      sequence: ++this.state.sequence,
      payload: {
        matchId: this.state.matchId,
        players: this.state.players,
      },
    });
    this.emitTurnStarted();
  }

  private enterAutoPlay(seat: Seat) {
    if (!this.state || seat.autoPlay) return;
    seat.autoPlay = true;
    this.broadcast({
      type: "AutoMode",
      sequence: ++this.state.sequence,
      payload: { playerId: seat.playerId, active: true, deadlineAt: null },
    });
  }

  private async performRoll(seat: Seat, auto: boolean) {
    if (!this.state) return;
    if (this.state.phase !== "ROLL" || this.state.pendingTurnStart || this.state.pendingNextFace) {
      if (!auto) this.reject(seat.playerId, "Busy");
      return;
    }
    if (this.state.rollChain === undefined) this.state.rollChain = [];
    if (this.state.moveQueue === undefined) this.state.moveQueue = [];

    const dice = fairDie(secureUint32);
    this.state.dice = dice;
    this.state.rollChain = [...this.state.rollChain, dice];

    // Three consecutive sixes → burn the turn (no move).
    const sixStreak = consecutiveTrailingSixes(this.state.rollChain);
    if (dice === 6 && sixStreak >= MAX_CHAIN_SIXES) {
      this.broadcast({
        type: "DiceResult",
        sequence: ++this.state.sequence,
        payload: {
          turnId: this.state.turnId,
          playerId: seat.playerId,
          color: seat.color,
          dice,
          legalPawnIds: [],
          rollChain: this.state.rollChain,
          moveQueue: [],
          mustRollAgain: false,
          chainBurnt: true,
          animateRoll: true,
          auto,
          deadlineAt: null,
          generatedAt: Date.now(),
        },
      });
      this.state.rollChain = [];
      this.state.moveQueue = [];
      this.advanceTurn();
      this.scheduleTurnStartedAfter(CLIENT_ROLL_ANIM_MS + 400);
      await this.scheduleWake();
      return;
    }

    // A six means roll again — humans may tap immediately; auto waits a short beat.
    if (dice === 6) {
      this.state.phase = "ROLL";
      this.state.deadlineAt = seat.autoPlay
        ? Date.now() + CLIENT_ROLL_ANIM_MS + SIX_REROLL_GAP_MS
        : Date.now() + ROLL_TIMEOUT_MS;
      this.broadcast({
        type: "DiceResult",
        sequence: ++this.state.sequence,
        payload: {
          turnId: this.state.turnId,
          playerId: seat.playerId,
          color: seat.color,
          dice,
          legalPawnIds: [],
          rollChain: this.state.rollChain,
          moveQueue: [],
          mustRollAgain: true,
          chainBurnt: false,
          animateRoll: true,
          rerollGapMs: seat.autoPlay ? SIX_REROLL_GAP_MS : 0,
          auto,
          deadlineAt: seat.autoPlay ? null : this.state.deadlineAt,
          generatedAt: Date.now(),
        },
      });
      await this.scheduleWake();
      return;
    }

    // Chain complete (ended on non-six): player chooses which remaining face to play.
    this.state.moveQueue = [...this.state.rollChain];
    this.openMoveChoice(seat, auto, /* animateRoll */ true);
    await this.scheduleWake();
  }

  /**
   * MOVE phase with the full remaining moveQueue. Player taps a piece then picks
   * a face (e.g. 6 or 4). legalPawnIds = any pawn usable with at least one face.
   */
  private openMoveChoice(seat: Seat, auto: boolean, animateRoll: boolean) {
    if (!this.state || this.state.phase === "FINISHED") return;
    if (this.state.moveQueue === undefined) this.state.moveQueue = [];

    const pruned = pruneUnusableFaces(this.state.pawns, seat.color, this.state.moveQueue);
    this.state.moveQueue = pruned.queue;
    const legalIds = allLegalPawnIds(this.state.pawns, seat.color, this.state.moveQueue);

    if (legalIds.length > 0 && this.state.moveQueue.length > 0) {
      const display =
        this.state.moveQueue[this.state.moveQueue.length - 1] ??
        this.state.dice ??
        this.state.rollChain[this.state.rollChain.length - 1] ??
        0;
      this.state.dice = display;
      this.state.phase = "MOVE";
      this.state.deadlineAt = seat.autoPlay
        ? Date.now() +
          (animateRoll ? CLIENT_ROLL_ANIM_MS + AUTO_VIEW_RESULT_MS : 0) +
          AUTO_PICK_PAUSE_MS
        : Date.now() + MOVE_TIMEOUT_MS;
      const pieceFaces: Record<string, number[]> = {};
      for (const id of legalIds) {
        pieceFaces[id] = facesForPiece(this.state.pawns, seat.color, this.state.moveQueue, id);
      }
      this.broadcast({
        type: "DiceResult",
        sequence: ++this.state.sequence,
        payload: {
          turnId: this.state.turnId,
          playerId: seat.playerId,
          color: seat.color,
          dice: display,
          legalPawnIds: legalIds,
          pieceFaces,
          rollChain: this.state.rollChain,
          moveQueue: this.state.moveQueue,
          skippedFaces: pruned.skipped,
          mustRollAgain: false,
          chainBurnt: false,
          animateRoll,
          auto,
          deadlineAt: seat.autoPlay ? null : this.state.deadlineAt,
          generatedAt: Date.now(),
        },
      });
      return;
    }

    this.broadcast({
      type: "DiceResult",
      sequence: ++this.state.sequence,
      payload: {
        turnId: this.state.turnId,
        playerId: seat.playerId,
        color: seat.color,
        dice: this.state.dice || this.state.rollChain[this.state.rollChain.length - 1] || 0,
        legalPawnIds: [],
        rollChain: this.state.rollChain,
        moveQueue: [],
        skippedFaces: pruned.skipped,
        mustRollAgain: false,
        chainBurnt: false,
        animateRoll,
        auto,
        deadlineAt: null,
        generatedAt: Date.now(),
      },
    });
    this.state.rollChain = [];
    this.state.moveQueue = [];
    this.advanceTurn();
    this.scheduleTurnStartedAfter(animateRoll ? CLIENT_ROLL_ANIM_MS : 350);
  }

  private async performMove(seat: Seat, pieceId: string, face: number, auto: boolean) {
    if (!this.state) return;
    if (this.state.phase !== "MOVE" || this.state.pendingTurnStart || this.state.pendingNextFace) {
      if (!auto) this.reject(seat.playerId, "Busy");
      return;
    }
    if (!this.state.moveQueue.includes(face)) {
      if (!auto) this.reject(seat.playerId, "IllegalFace");
      return;
    }
    const allowed = facesForPiece(this.state.pawns, seat.color, this.state.moveQueue, pieceId);
    if (!allowed.includes(face)) {
      if (!auto) this.reject(seat.playerId, "IllegalMove");
      return;
    }
    try {
      const outcome = applyMove(this.state.pawns, pieceId, face, seat.color);
      this.state.pawns = outcome.pawns;
      void awardMoveProgressXp(this.env, {
        matchId: this.state.matchId,
        turnId: this.state.turnId,
        actorPubkey: seat.playerId,
        actorIsBot: seat.isBot,
        pieceId,
        capturedPieceIds: outcome.capturedPieceIds,
        finished: outcome.finished,
      });
      this.state.dice = face;
      this.state.deadlineAt = null;
      this.state.moveQueue = consumePlayedFace(this.state.moveQueue, face);

      // Arrow: +1 on track arrows, or last track cell → first home cell.
      let arrowBonus = false;
      let arrowToStep: number | null = null;
      if (outcome.toStep === HOME_ENTRY_FROM_STEP) {
        this.state.pawns = this.state.pawns.map((p) =>
          p.id === pieceId ? { ...p, step: HOME_ENTRY_TO_STEP } : p,
        );
        outcome.path = [...outcome.path, HOME_ENTRY_TO_STEP];
        outcome.toStep = HOME_ENTRY_TO_STEP;
        arrowToStep = HOME_ENTRY_TO_STEP;
        arrowBonus = true;
      } else if (outcome.toStep >= 0 && outcome.toStep <= 50) {
        const fromAbs = absoluteTrackIndex(seat.color, outcome.toStep);
        const toAbs = ARROW_TRACK_JUMPS[fromAbs];
        if (toAbs !== undefined) {
          const jumped = stepForAbsolute(seat.color, toAbs);
          if (jumped !== null && jumped !== outcome.toStep) {
            this.state.pawns = this.state.pawns.map((p) =>
              p.id === pieceId ? { ...p, step: jumped } : p,
            );
            outcome.path = [...outcome.path, jumped];
            outcome.toStep = jumped;
            arrowToStep = jumped;
            arrowBonus = true;
          }
        }
      }

      const win = winner(this.state.pawns, this.state.players);
      const pathMs =
        Math.max(1, outcome.path.length) * CLIENT_STEP_MS +
        220 +
        outcome.capturedPieceIds.length * CLIENT_CAPTURE_RETURN_MS +
        (arrowBonus ? 480 : 0);
      const handoffMs = pathMs + TURN_HANDOFF_BUFFER_MS;

      this.broadcast({
        type: "BoardUpdated",
        sequence: ++this.state.sequence,
        payload: {
          turnId: this.state.turnId,
          playerId: seat.playerId,
          pieceId,
          fromStep: outcome.fromStep,
          toStep: outcome.toStep,
          dice: face,
          path: outcome.path,
          capturedPieceIds: outcome.capturedPieceIds,
          pawns: this.state.pawns,
          finished: outcome.finished,
          moveQueue: this.state.moveQueue,
          auto,
          arrowBonus,
          arrowToStep,
          generatedAt: Date.now(),
        },
      });

      if (win) {
        this.state.winner = win;
        this.state.phase = "FINISHED";
        this.state.pendingTurnStart = false;
        this.state.pendingNextFace = false;
        this.state.moveQueue = [];
        this.state.rollChain = [];
        this.broadcast({
          type: "MatchFinished",
          sequence: ++this.state.sequence,
          payload: { winner: win },
        });
      if (this.state) {
        await settleMatchEconomy(this.env, {
          matchId: this.state.matchId,
          mode: "arrow_4p",
          winnerColor: this.state.winner,
          seats: this.state.seats,
        });
      }
        await this.scheduleWake();
        return;
      }

      if (arrowBonus) {
        // Same seat rolls again after the jump animation.
        this.state.phase = "ROLL";
        this.state.dice = 0;
        this.state.rollChain = [];
        this.state.moveQueue = [];
        this.state.turnId = crypto.randomUUID();
        this.scheduleTurnStartedAfter(handoffMs);
      } else if (outcome.capturedPieceIds.length > 0 || outcome.finished) {
        this.state.phase = "ROLL";
        this.state.dice = 0;
        this.state.rollChain = [];
        this.state.moveQueue = [];
        this.state.turnId = crypto.randomUUID();
        this.scheduleTurnStartedAfter(handoffMs);
      } else if (this.state.moveQueue.length > 0) {
        this.scheduleNextFaceAfter(pathMs);
      } else {
        this.state.rollChain = [];
        this.advanceTurn();
        this.scheduleTurnStartedAfter(handoffMs);
      }
    } catch {
      if (!auto) this.reject(seat.playerId, "IllegalMove");
      else {
        this.state.moveQueue = [];
        this.state.rollChain = [];
        this.advanceTurn();
        this.scheduleTurnStartedAfter(CLIENT_ROLL_ANIM_MS);
      }
    }
    await this.scheduleWake();
  }

  private async eliminateSeat(playerId: string, reason: "forfeit" | "opponent_absent"): Promise<void> {
    if (!this.state || this.state.phase === "FINISHED") return;
    const seat = this.state.seats.find((s) => s.playerId === playerId);
    if (!seat || seat.eliminated) return;
    seat.eliminated = true;
    seat.autoPlay = true;
    seat.disconnectedAt = null;

    const alive = this.state.seats.filter((s) => !s.eliminated);
    if (alive.length === 1) {
      const champ = alive[0]!;
      this.state.winner = champ.color;
      this.state.phase = "FINISHED";
      this.state.deadlineAt = null;
      this.state.pendingTurnStart = false;
      this.state.pendingNextFace = false;
      this.broadcast({
        type: "MatchFinished",
        sequence: ++this.state.sequence,
        payload: {
          winner: champ.color,
          reason,
          forfeitedPlayerId: reason === "forfeit" ? playerId : undefined,
          absentPlayerId: reason === "opponent_absent" ? playerId : undefined,
        },
      });
      if (this.state) {
        await settleMatchEconomy(this.env, {
          matchId: this.state.matchId,
          mode: "arrow_4p",
          winnerColor: this.state.winner,
          seats: this.state.seats,
        });
      }
      return;
    }

    // If it was their turn, hand off.
    const cur = this.state.seats[this.state.currentIndex];
    if (cur?.playerId === playerId) {
      this.advanceTurn();
      this.scheduleTurnStartedAfter(400);
    }
  }

  private advanceTurn() {
    if (!this.state) return;
    const n = this.state.seats.length;
    for (let i = 0; i < n; i += 1) {
      this.state.currentIndex = (this.state.currentIndex + 1) % n;
      if (!this.state.seats[this.state.currentIndex]!.eliminated) break;
    }
    this.state.phase = "ROLL";
    this.state.dice = 0;
    this.state.rollChain = [];
    this.state.moveQueue = [];
    this.state.pendingNextFace = false;
    this.state.turnId = crypto.randomUUID();
    this.state.deadlineAt = null;
  }

  /**
   * Wait for clients to finish the previous walk, then emit TurnStarted.
   * The human roll timer starts ONLY at emit time (fresh window) — never during the walk.
   */
  private scheduleTurnStartedAfter(delayMs: number) {
    if (!this.state || this.state.phase === "FINISHED") return;
    this.state.pendingTurnStart = true;
    this.state.pendingNextFace = false;
    this.state.phase = "ROLL";
    this.state.deadlineAt = Date.now() + Math.max(delayMs, 50);
  }

  private scheduleNextFaceAfter(delayMs: number) {
    if (!this.state || this.state.phase === "FINISHED") return;
    this.state.pendingNextFace = true;
    this.state.pendingTurnStart = false;
    this.state.phase = "MOVE";
    this.state.deadlineAt = Date.now() + Math.max(delayMs, 50);
  }

  private emitTurnStarted() {
    if (!this.state || this.state.phase === "FINISHED") return;
    let guard = 0;
    while (this.state.seats[this.state.currentIndex]?.eliminated && guard < this.state.seats.length) {
      this.state.currentIndex = (this.state.currentIndex + 1) % this.state.seats.length;
      guard += 1;
    }
    const seat = this.state.seats[this.state.currentIndex]!;
    if (seat.eliminated) return;
    this.state.pendingTurnStart = false;
    this.state.pendingNextFace = false;
    this.state.phase = "ROLL";
    this.state.rollChain = [];
    this.state.moveQueue = [];
    this.state.dice = 0;

    if (seat.autoPlay || seat.isBot) {
      // Bots / sticky auto: no ack needed — pace then roll.
      this.state.turnAwaitingAck = false;
      this.state.turnAckDeadlineAt = null;
      this.state.deadlineAt = Date.now() + AUTO_PRE_ROLL_MS;
      this.broadcastTurnStarted(seat, /* auto */ true);
      void this.scheduleWake();
      return;
    }

    // Human: announce turn NOW, but do NOT start the steal-timer until TurnAck.
    // That way a phone still finishing the opponent walk never loses the window.
    this.state.turnAwaitingAck = true;
    this.state.turnAckResends = 0;
    this.state.turnAckDeadlineAt = Date.now() + TURN_ACK_WAIT_MS;
    this.state.deadlineAt = this.state.turnAckDeadlineAt;
    this.broadcastTurnStarted(seat, /* auto */ false);
    void this.scheduleWake();
  }

  private broadcastTurnStarted(seat: Seat, auto: boolean) {
    if (!this.state) return;
    this.broadcast({
      type: "TurnStarted",
      sequence: ++this.state.sequence,
      payload: {
        turnId: this.state.turnId,
        playerId: seat.playerId,
        color: seat.color,
        phase: "ROLL",
        // null while awaiting ack — client shows "your turn" without a draining timer yet.
        deadlineAt: auto || this.state.turnAwaitingAck ? null : this.state.deadlineAt,
        auto,
        rollChain: [],
        yourTurn: true,
        awaitingAck: this.state.turnAwaitingAck,
      },
    });
  }

  private armRollDeadlineAfterAck(seat: Seat) {
    if (!this.state) return;
    this.state.turnAwaitingAck = false;
    this.state.turnAckDeadlineAt = null;
    this.state.turnAckResends = 0;
    this.state.deadlineAt = Date.now() + ROLL_TIMEOUT_MS;
    // Broadcast so every client can show the roll countdown on this seat's avatar.
    this.broadcast({
      type: "TurnArmed",
      sequence: ++this.state.sequence,
      payload: {
        turnId: this.state.turnId,
        playerId: seat.playerId,
        deadlineAt: this.state.deadlineAt,
      },
    });
  }

  private async onTurnAckTimeout() {
    if (!this.state || !this.state.turnAwaitingAck) return;
    const seat = this.state.seats[this.state.currentIndex];
    if (!seat || seat.isBot || seat.autoPlay) {
      this.state.turnAwaitingAck = false;
      this.state.turnAckDeadlineAt = null;
      return;
    }
    if (this.state.turnAckResends < 2) {
      this.state.turnAckResends += 1;
      this.state.turnAckDeadlineAt = Date.now() + TURN_ACK_WAIT_MS;
      this.state.deadlineAt = this.state.turnAckDeadlineAt;
      this.broadcastTurnStarted(seat, false);
      return;
    }
    // Still no ack — arm the normal timer so the match cannot soft-lock.
    this.armRollDeadlineAfterAck(seat);
  }

  private maybeResendTurnTo(playerId: string) {
    if (!this.state || !this.state.started || this.state.phase === "FINISHED") return;
    if (!this.state.turnAwaitingAck) return;
    if (this.state.pendingTurnStart || this.state.pendingNextFace) return;
    const seat = this.state.seats[this.state.currentIndex];
    if (!seat || seat.playerId !== playerId || seat.isBot || !seat.socket) return;
    this.send(seat.socket, {
      type: "TurnStarted",
      sequence: ++this.state.sequence,
      payload: {
        turnId: this.state.turnId,
        playerId: seat.playerId,
        color: seat.color,
        phase: "ROLL",
        deadlineAt: null,
        auto: false,
        rollChain: [],
        yourTurn: true,
        awaitingAck: true,
      },
    });
  }

  private async scheduleWake() {
    if (!this.state || this.state.phase === "FINISHED") {
      try {
        await this.ctx.storage.deleteAlarm();
      } catch {
        // ignore
      }
      return;
    }
    const times: number[] = [];
    if (this.state.deadlineAt != null) times.push(this.state.deadlineAt);
    if (this.state.turnAckDeadlineAt != null) times.push(this.state.turnAckDeadlineAt);
    if (!this.state.started && this.state.introDeadlineAt != null) {
      times.push(this.state.introDeadlineAt);
    }
    for (const seat of this.state.seats) {
      if (seat.isBot) continue;
      if (seat.disconnectedAt != null) {
        times.push(seat.disconnectedAt + ABSENT_WIN_MS);
      }
    }
    if (times.length === 0) {
      try {
        await this.ctx.storage.deleteAlarm();
      } catch {
        // ignore
      }
      return;
    }
    const at = Math.min(...times);
    await this.ctx.storage.setAlarm(Math.max(at, Date.now() + 50));
  }

  private snapshotPayload(playerId: string) {
    const s = this.state!;
    const current = s.seats[s.currentIndex];
    return {
      matchId: s.matchId,
      you: playerId,
      players: s.players,
      pawns: s.pawns,
      currentPlayerId: current?.playerId,
      phase: s.phase,
      dice: s.dice,
      turnId: s.turnId,
      winner: s.winner,
      rollChain: s.rollChain ?? [],
      moveQueue: s.moveQueue ?? [],
      deadlineAt:
        current?.autoPlay || s.pendingTurnStart || s.pendingNextFace ? null : s.deadlineAt,
      awayPlayerIds: s.seats.filter((x) => x.disconnectedAt != null).map((x) => x.playerId),
      autoPlayerIds: s.seats.filter((x) => x.autoPlay).map((x) => x.playerId),
    };
  }

  private reject(playerId: string, code: string) {
    const seat = this.state?.seats.find((s) => s.playerId === playerId);
    if (!seat?.socket) return;
    this.send(seat.socket, { type: "Rejected", sequence: this.state!.sequence, payload: { code } });
  }

  private broadcast(event: { type: string; sequence: number; payload: unknown }) {
    if (!this.state) return;
    for (const seat of this.state.seats) {
      if (seat.socket) this.send(seat.socket, event);
    }
  }

  private send(socket: WebSocket, event: unknown) {
    try {
      socket.send(JSON.stringify(event));
    } catch {
      // ignore closed sockets
    }
  }
}

function consecutiveTrailingSixes(chain: number[]): number {
  let n = 0;
  for (let i = chain.length - 1; i >= 0; i -= 1) {
    if (chain[i] === 6) n += 1;
    else break;
  }
  return n;
}
