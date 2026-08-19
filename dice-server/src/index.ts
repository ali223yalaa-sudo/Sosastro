import { fairDie, secureUint32 } from "./fairDie";

export { fairDie } from "./fairDie";
export { GameRoom } from "./GameRoom";
export { Matchmaker } from "./Matchmaker";
export { GameRoom4 } from "./GameRoom4";
export { Matchmaker4 } from "./Matchmaker4";
export { GameRoomArrow } from "./GameRoomArrow";
export { MatchmakerArrow } from "./MatchmakerArrow";
export { GameRoomArrow1 } from "./GameRoomArrow1";
export { MatchmakerArrow1 } from "./MatchmakerArrow1";
export { TeamLobby } from "./team/TeamLobby";
export { TeamMatchmaker } from "./team/TeamMatchmaker";
export { GameRoomTeam2v2 } from "./team/GameRoomTeam2v2";
export { TeamInviteInbox } from "./team/TeamInviteInbox";

export interface Env {
  /** 1v1 classic — do not repurpose. */
  GAME_ROOM: DurableObjectNamespace;
  MATCHMAKER: DurableObjectNamespace;
  /** 4-player classic — do not repurpose. */
  GAME_ROOM_4: DurableObjectNamespace;
  MATCHMAKER_4: DurableObjectNamespace;
  /** Arrow mode 4p (independent stack). */
  GAME_ROOM_ARROW: DurableObjectNamespace;
  MATCHMAKER_ARROW: DurableObjectNamespace;
  /** Arrow mode 1v1 (independent stack). */
  GAME_ROOM_ARROW_1: DurableObjectNamespace;
  MATCHMAKER_ARROW_1: DurableObjectNamespace;
  /** Team 2v2 — isolated stack (never repurpose classic/arrow DOs). */
  TEAM_LOBBY: DurableObjectNamespace;
  TEAM_MATCHMAKER: DurableObjectNamespace;
  GAME_ROOM_TEAM: DurableObjectNamespace;
  TEAM_INVITE_INBOX: DurableObjectNamespace;
  /** Isolated economy Worker base URL (no trailing slash). */
  ECONOMY_BASE_URL?: string;
  /** Shared with economy Worker for bind/payout (Wrangler secret). */
  GAME_WORKER_SECRET?: string;
  /** Optional dedicated seat-token HMAC (falls back to GAME_WORKER_SECRET). */
  SEAT_TOKEN_SECRET?: string;
}

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store, no-cache, must-revalidate",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS, GET",
  "access-control-allow-headers": "content-type",
} as const;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: JSON_HEADERS });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true });
    }

    if (request.method === "POST" && url.pathname === "/roll") {
      const value = fairDie(secureUint32);
      return json({
        value,
        rollId: crypto.randomUUID(),
        generatedAt: Date.now(),
        source: "cloudflare-web-crypto",
      });
    }

    // ——— Classic 1v1 (unchanged) ———
    if (url.pathname === "/match") {
      const id = env.MATCHMAKER.idFromName("global");
      return env.MATCHMAKER.get(id).fetch(request);
    }

    const roomMatch = url.pathname.match(/^\/room\/([^/]+)$/);
    if (roomMatch) {
      const matchId = decodeURIComponent(roomMatch[1]!);
      const id = env.GAME_ROOM.idFromName(matchId);
      return env.GAME_ROOM.get(id).fetch(request);
    }

    // ——— Classic 4p (unchanged) ———
    if (url.pathname === "/match4") {
      const id = env.MATCHMAKER_4.idFromName("global4");
      return env.MATCHMAKER_4.get(id).fetch(request);
    }

    const room4Match = url.pathname.match(/^\/room4\/([^/]+)$/);
    if (room4Match) {
      const matchId = decodeURIComponent(room4Match[1]!);
      const id = env.GAME_ROOM_4.idFromName(matchId);
      return env.GAME_ROOM_4.get(id).fetch(request);
    }

    // ——— Arrow 4p (independent DOs) ———
    if (url.pathname === "/matchArrow") {
      const id = env.MATCHMAKER_ARROW.idFromName("globalArrow");
      return env.MATCHMAKER_ARROW.get(id).fetch(request);
    }

    const roomArrowMatch = url.pathname.match(/^\/roomArrow\/([^/]+)$/);
    if (roomArrowMatch) {
      const matchId = decodeURIComponent(roomArrowMatch[1]!);
      const id = env.GAME_ROOM_ARROW.idFromName(matchId);
      return env.GAME_ROOM_ARROW.get(id).fetch(request);
    }

    // ——— Arrow 1v1 (independent DOs) ———
    if (url.pathname === "/matchArrow1") {
      const id = env.MATCHMAKER_ARROW_1.idFromName("globalArrow1");
      return env.MATCHMAKER_ARROW_1.get(id).fetch(request);
    }

    const roomArrow1Match = url.pathname.match(/^\/roomArrow1\/([^/]+)$/);
    if (roomArrow1Match) {
      const matchId = decodeURIComponent(roomArrow1Match[1]!);
      const id = env.GAME_ROOM_ARROW_1.idFromName(matchId);
      return env.GAME_ROOM_ARROW_1.get(id).fetch(request);
    }

    // ——— Team 2v2 (isolated DO stack — never touches classic/arrow routes above) ———
    if (request.method === "POST" && url.pathname === "/team/session/create") {
      const body = await request.text();
      const lobbyId = crypto.randomUUID();
      const id = env.TEAM_LOBBY.idFromName(lobbyId);
      // DO pathname must be exactly /create (not /lobby/create).
      const resp = await env.TEAM_LOBBY.get(id).fetch(
        new Request(`https://team/create?lobbyId=${encodeURIComponent(lobbyId)}`, {
          method: "POST",
          body,
          headers: request.headers,
        }),
      );
      const data = (await resp.json()) as Record<string, unknown>;
      return json({ ...data, lobbyId }, resp.status);
    }

    const teamLobbyMatch = url.pathname.match(/^\/team\/lobby\/([^/]+)(\/.*)?$/);
    if (teamLobbyMatch) {
      const lobbyId = decodeURIComponent(teamLobbyMatch[1]!);
      const sub = teamLobbyMatch[2] || "";
      const id = env.TEAM_LOBBY.idFromName(lobbyId);
      const inner = new URL(request.url);
      inner.pathname = sub || "/snapshot";
      return env.TEAM_LOBBY.get(id).fetch(
        new Request(inner.toString(), { method: request.method, body: request.body, headers: request.headers }),
      );
    }

    if (url.pathname === "/team/match") {
      const id = env.TEAM_MATCHMAKER.idFromName("globalTeam");
      return env.TEAM_MATCHMAKER.get(id).fetch(request);
    }

    const teamInboxMatch = url.pathname.match(/^\/team\/inbox\/([^/]+)(\/.*)?$/);
    if (teamInboxMatch) {
      const pubkey = decodeURIComponent(teamInboxMatch[1]!);
      const sub = teamInboxMatch[2] || "/pending";
      const id = env.TEAM_INVITE_INBOX.idFromName(pubkey.toLowerCase());
      // DO expects /pending|/push|/ack — do not prefix with /inbox.
      return env.TEAM_INVITE_INBOX.get(id).fetch(
        new Request(`https://team${sub}`, { method: request.method, body: request.body, headers: request.headers }),
      );
    }

    const roomTeamMatch = url.pathname.match(/^\/roomTeam\/([^/]+)$/);
    if (roomTeamMatch) {
      const matchId = decodeURIComponent(roomTeamMatch[1]!);
      const id = env.GAME_ROOM_TEAM.idFromName(matchId);
      return env.GAME_ROOM_TEAM.get(id).fetch(request);
    }

    return json({ error: "NotFound" }, 404);
  },
};
