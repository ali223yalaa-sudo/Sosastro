/**
 * Shared ticket helpers for all Matchmaker* Durable Objects.
 */
import { bindMatchTickets, type MatchMode } from "./economyBridge";
import type { Env } from "./index";
import { mintSeatToken } from "./seatAuth";

export type TicketWaiting = {
  playerId: string;
  ticketId: string;
  name: string;
  pieceStyleId: string;
  diceStyleId: string;
  socket: WebSocket;
  joinedAt: number;
};

export function rejectTicket(socket: WebSocket, reason: string): void {
  try {
    socket.send(JSON.stringify({ type: "TicketRejected", payload: { reason } }));
    socket.close(1008, reason.slice(0, 80));
  } catch {
    // ignore
  }
}

export async function prepareMatchWithTickets(
  env: Env,
  mode: MatchMode,
  humans: TicketWaiting[],
): Promise<{ matchId: string; seatTokens: Record<string, string> } | { error: string }> {
  for (const h of humans) {
    if (!h.ticketId || h.ticketId.length > 80) {
      return { error: "missing_ticket" };
    }
    if (!/^[0-9a-f]{64}$/i.test(h.playerId)) {
      return { error: "bad_player" };
    }
  }
  const matchId = crypto.randomUUID();
  const bound = await bindMatchTickets(env, {
    matchId,
    mode,
    tickets: humans.map((h) => ({ ticketId: h.ticketId, pubkey: h.playerId })),
  });
  if (!bound.ok) {
    return { error: bound.reason };
  }

  const seatTokens: Record<string, string> = {};
  for (const h of humans) {
    const token = await mintSeatToken(env, { matchId, playerId: h.playerId });
    if (!token) {
      return { error: "seat_secret_missing" };
    }
    seatTokens[h.playerId] = token;
  }
  return { matchId, seatTokens };
}
