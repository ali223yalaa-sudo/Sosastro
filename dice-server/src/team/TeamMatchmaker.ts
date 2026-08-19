/**
 * Team 2v2 matchmaker — matches two ready teams (4 humans).
 * Isolated from Matchmaker4 / GameRoom4.
 */
import { DurableObject } from "cloudflare:workers";
import type { Env } from "../index";
import { prepareMatchWithTickets, type TicketWaiting } from "../matchTicketsShared";
import { buildRoomWebsocketUrl } from "../seatAuth";

type TeamMember = {
  playerId: string;
  name: string;
  ticketId: string | null;
  pieceStyleId: string;
  diceStyleId: string;
};

type QueuedTeam = {
  lobbyId: string;
  mode: "classic" | "arrow";
  stake: number;
  members: TeamMember[];
  enqueuedAt: number;
};

export class TeamMatchmaker extends DurableObject<Env> {
  private queue: QueuedTeam[] = [];
  private lastHost = "amethyst-ludo-dice.www-hmzhh123-com.workers.dev";

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    this.lastHost = url.host;

    if (request.method === "POST" && url.pathname === "/enqueue") {
      const body = (await request.json()) as QueuedTeam;
      if (body.members.length !== 2) {
        return Response.json({ error: "NeedTwoMembers" }, { status: 400 });
      }
      for (const m of body.members) {
        if (!/^[0-9a-f]{64}$/i.test(m.playerId)) {
          return Response.json({ error: "BadPlayer" }, { status: 400 });
        }
      }
      this.queue = this.queue.filter((t) => t.lobbyId !== body.lobbyId);
      this.queue.push({ ...body, enqueuedAt: Date.now() });
      void this.tryMatch();
      return Response.json({ ok: true, queuePosition: this.queue.length });
    }

    return Response.json({ error: "NotFound" }, { status: 404 });
  }

  private async tryMatch() {
    if (this.queue.length < 2) return;
    const teamA = this.queue.shift()!;
    const teamB = this.queue.shift()!;
    if (teamA.mode !== teamB.mode || teamA.stake !== teamB.stake) {
      this.queue.unshift(teamB, teamA);
      return;
    }

    const matchMode = teamA.mode === "arrow" ? "team_arrow_2v2" : "team_classic_2v2";
    const humans: TicketWaiting[] = [];
    for (const t of [teamA, teamB]) {
      for (const m of t.members) {
        if (!m.ticketId) continue;
        humans.push({
          playerId: m.playerId,
          name: m.name,
          ticketId: m.ticketId,
          pieceStyleId: m.pieceStyleId,
          diceStyleId: m.diceStyleId,
          socket: null as unknown as WebSocket,
          joinedAt: Date.now(),
        });
      }
    }

    const prepared = await prepareMatchWithTickets(this.env, matchMode, humans);
    if ("error" in prepared) {
      this.queue.unshift(teamB, teamA);
      return;
    }

    const { matchId, seatTokens } = prepared;
    const players = [
      { ...teamA.members[0]!, team: "A" },
      { ...teamA.members[1]!, team: "A" },
      { ...teamB.members[0]!, team: "B" },
      { ...teamB.members[1]!, team: "B" },
    ].map((p, i) => ({
      playerId: p.playerId,
      name: p.name,
      isBot: false,
      pieceStyleId: p.pieceStyleId,
      diceStyleId: p.diceStyleId,
      team: p.team,
      seatIndex: i,
    }));

    const roomId = this.env.GAME_ROOM_TEAM.idFromName(matchId);
    await this.env.GAME_ROOM_TEAM.get(roomId).fetch(
      new Request("https://team/boot", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ matchId, matchMode, players }),
      }),
    );

    const wsBase = `/roomTeam/${encodeURIComponent(matchId)}`;
    const urls: Record<string, string> = {};
    for (const p of players) {
      const token = seatTokens[p.playerId];
      if (!token) continue;
      urls[p.playerId] = buildRoomWebsocketUrl({
        host: this.lastHost,
        path: wsBase,
        playerId: p.playerId,
        seatToken: token,
      });
    }

    // Notify team lobbies via alarm/alchemy - lobbies poll WS; for now store match urls in stub
    await this.notifyTeams(teamA.lobbyId, teamB.lobbyId, matchId, urls);
  }

  private async notifyTeams(
    lobbyA: string,
    lobbyB: string,
    matchId: string,
    urls: Record<string, string>,
  ) {
    for (const lid of [lobbyA, lobbyB]) {
      try {
        const id = this.env.TEAM_LOBBY.idFromName(lid);
        await this.env.TEAM_LOBBY.get(id).fetch(
          new Request("https://team/match-found", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ matchId, urls }),
          }),
        );
      } catch {
        // best-effort
      }
    }
  }
}
