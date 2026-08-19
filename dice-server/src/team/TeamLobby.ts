/**
 * Team lobby — host + partner seats, invite by pubkey, start matchmaking as a unit.
 * Isolated from Matchmaker / Matchmaker4 / Arrow stacks.
 */
import { DurableObject } from "cloudflare:workers";
import type { Env } from "../index";
import { verifyTeamToken, mintTeamToken } from "./teamAuth";
import { inviteExpiresAt, randomTeamCode, type TeamInvite } from "./TeamInviteInbox";

export type TeamLobbyMode = "classic" | "arrow";

type PartnerState = {
  pubkey: string;
  name: string;
  pieceStyleId: string;
  diceStyleId: string;
  accepted: boolean;
  ticketId: string | null;
};

type LobbyState = {
  lobbyId: string;
  teamCode: string;
  host: PartnerState;
  partner: PartnerState | null;
  mode: TeamLobbyMode;
  stake: number;
  status: "waiting_partner" | "ready" | "searching" | "in_match" | "closed";
  createdAt: number;
  matchId: string | null;
};

export class TeamLobby extends DurableObject<Env> {
  private state: LobbyState | null = null;
  private sockets: Map<string, WebSocket> = new Map();

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname === "/create") {
      const urlLobbyId = new URL(request.url).searchParams.get("lobbyId");
      return this.handleCreate(request, urlLobbyId);
    }

    if (!this.state) {
      return Response.json({ error: "LobbyNotFound" }, { status: 404 });
    }

    if (request.method === "GET" && url.pathname === "/snapshot") {
      return Response.json({ ok: true, lobby: this.publicSnapshot() });
    }

    if (request.method === "POST" && url.pathname === "/invite") {
      return this.handleInvite(request);
    }

    if (request.method === "POST" && url.pathname === "/respond") {
      return this.handleRespond(request);
    }

    if (request.method === "POST" && url.pathname === "/join-code") {
      return this.handleJoinCode(request);
    }

    if (request.method === "POST" && url.pathname === "/start") {
      return this.handleStart(request);
    }

    if (request.method === "POST" && url.pathname === "/match-found") {
      const body = (await request.json()) as {
        matchId?: string;
        urls?: Record<string, string>;
      };
      if (this.state && body.matchId) {
        this.state.matchId = body.matchId;
        this.state.status = "in_match";
        this.broadcast({ type: "MatchFound", payload: body });
      }
      return Response.json({ ok: true });
    }

    if (request.headers.get("Upgrade") === "websocket") {
      return this.handleWs(request, url);
    }

    return Response.json({ error: "NotFound" }, { status: 404 });
  }

  private async handleCreate(request: Request, externalLobbyId: string | null): Promise<Response> {
    if (this.state) {
      return Response.json({ ok: true, lobby: this.publicSnapshot(), hostToken: await this.hostToken() });
    }
    const body = (await request.json()) as {
      sessionToken?: string;
      playerId?: string;
      name?: string;
      mode?: TeamLobbyMode;
      stake?: number;
      pieceStyleId?: string;
      diceStyleId?: string;
    };
    const playerId = (body.playerId || "").toLowerCase();
    const sessionToken = (body.sessionToken || "").trim();
    const v = await verifyTeamToken(this.env, {
      token: sessionToken,
      expectedType: "team_session",
      playerId,
    });
    if (!v.ok) return Response.json({ error: "Forbidden", reason: v.reason }, { status: 403 });

    const lobbyId = (externalLobbyId || crypto.randomUUID()).trim();
    const teamCode = randomTeamCode();
    this.state = {
      lobbyId,
      teamCode,
      host: {
        pubkey: playerId,
        name: (body.name || "Player").slice(0, 40),
        pieceStyleId: (body.pieceStyleId || "disc").slice(0, 32),
        diceStyleId: (body.diceStyleId || "cdr_classic").slice(0, 40),
        accepted: true,
        ticketId: null,
      },
      partner: null,
      mode: body.mode === "arrow" ? "arrow" : "classic",
      stake: Math.max(10, Math.min(100, Number(body.stake) || 25)),
      status: "waiting_partner",
      createdAt: Date.now(),
      matchId: null,
    };

    const hostToken = await mintTeamToken(this.env, {
      typ: "lobby_host",
      pid: playerId,
      lid: lobbyId,
      ttlSec: 3600,
    });

    return Response.json({ ok: true, lobby: this.publicSnapshot(), hostToken });
  }

  private async handleInvite(request: Request): Promise<Response> {
    const body = (await request.json()) as {
      hostToken?: string;
      hostPubkey?: string;
      inviteePubkey?: string;
      inviteeName?: string;
    };
    const hostPubkey = (body.hostPubkey || "").toLowerCase();
    const invitee = (body.inviteePubkey || "").toLowerCase();
    const v = await verifyTeamToken(this.env, {
      token: (body.hostToken || "").trim(),
      expectedType: "lobby_host",
      playerId: hostPubkey,
      lobbyId: this.state!.lobbyId,
    });
    if (!v.ok) return Response.json({ error: "Forbidden" }, { status: 403 });
    if (hostPubkey !== this.state!.host.pubkey) {
      return Response.json({ error: "NotHost" }, { status: 403 });
    }
    if (!/^[0-9a-f]{64}$/.test(invitee)) {
      return Response.json({ error: "BadInvitee" }, { status: 400 });
    }
    if (this.state!.partner?.accepted) {
      return Response.json({ error: "PartnerTaken" }, { status: 409 });
    }

    this.state!.partner = {
      pubkey: invitee,
      name: (body.inviteeName || "Player").slice(0, 40),
      pieceStyleId: "disc",
      diceStyleId: "cdr_classic",
      accepted: false,
      ticketId: null,
    };

    const inviteId = crypto.randomUUID();
    const now = Date.now();
    const invite: TeamInvite = {
      inviteId,
      lobbyId: this.state!.lobbyId,
      teamCode: this.state!.teamCode,
      hostPubkey,
      hostName: this.state!.host.name,
      mode: this.state!.mode,
      stake: this.state!.stake,
      createdAt: now,
      expiresAt: inviteExpiresAt(now),
    };

    const inboxId = this.env.TEAM_INVITE_INBOX.idFromName(invitee);
    await this.env.TEAM_INVITE_INBOX.get(inboxId).fetch(
      new Request("https://team/push", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-game-secret": this.env.GAME_WORKER_SECRET || "",
        },
        body: JSON.stringify(invite),
      }),
    );

    this.broadcast({ type: "InviteSent", payload: { inviteePubkey: invitee } });
    return Response.json({ ok: true, inviteId, teamCode: this.state!.teamCode });
  }

  private async handleRespond(request: Request): Promise<Response> {
    const body = (await request.json()) as {
      sessionToken?: string;
      playerId?: string;
      accept?: boolean;
      name?: string;
      pieceStyleId?: string;
      diceStyleId?: string;
      ticketId?: string;
    };
    const playerId = (body.playerId || "").toLowerCase();
    const v = await verifyTeamToken(this.env, {
      token: (body.sessionToken || "").trim(),
      expectedType: "team_session",
      playerId,
    });
    if (!v.ok) return Response.json({ error: "Forbidden" }, { status: 403 });

    const partner = this.state!.partner;
    if (!partner || partner.pubkey !== playerId) {
      return Response.json({ error: "NotInvited" }, { status: 403 });
    }

    if (body.accept) {
      partner.accepted = true;
      if (body.name && body.name.trim()) {
        partner.name = body.name.trim().slice(0, 40);
      }
      partner.pieceStyleId = (body.pieceStyleId || "disc").slice(0, 32);
      partner.diceStyleId = (body.diceStyleId || "cdr_classic").slice(0, 40);
      partner.ticketId = (body.ticketId || "").trim() || null;
      this.state!.status = "ready";
    } else {
      this.state!.partner = null;
      this.state!.status = "waiting_partner";
    }

    this.broadcast({ type: "PartnerUpdated", payload: this.publicSnapshot() });
    return Response.json({ ok: true, lobby: this.publicSnapshot() });
  }

  private async handleJoinCode(request: Request): Promise<Response> {
    const body = (await request.json()) as {
      sessionToken?: string;
      playerId?: string;
      name?: string;
      teamCode?: string;
    };
    const playerId = (body.playerId || "").toLowerCase();
    const code = (body.teamCode || "").trim();
    const v = await verifyTeamToken(this.env, {
      token: (body.sessionToken || "").trim(),
      expectedType: "team_session",
      playerId,
    });
    if (!v.ok) return Response.json({ error: "Forbidden" }, { status: 403 });
    if (code !== this.state!.teamCode) {
      return Response.json({ error: "BadCode" }, { status: 400 });
    }
    if (this.state!.partner?.accepted) {
      return Response.json({ error: "Full" }, { status: 409 });
    }
    this.state!.partner = {
      pubkey: playerId,
      name: (body.name || "Player").slice(0, 40),
      pieceStyleId: "disc",
      diceStyleId: "cdr_classic",
      accepted: true,
      ticketId: null,
    };
    this.state!.status = "ready";
    this.broadcast({ type: "PartnerUpdated", payload: this.publicSnapshot() });
    return Response.json({ ok: true, lobby: this.publicSnapshot() });
  }

  private async handleStart(request: Request): Promise<Response> {
    const body = (await request.json()) as {
      hostToken?: string;
      hostPubkey?: string;
      hostTicketId?: string;
      partnerTicketId?: string;
    };
    const hostPubkey = (body.hostPubkey || "").toLowerCase();
    const v = await verifyTeamToken(this.env, {
      token: (body.hostToken || "").trim(),
      expectedType: "lobby_host",
      playerId: hostPubkey,
      lobbyId: this.state!.lobbyId,
    });
    if (!v.ok) return Response.json({ error: "Forbidden" }, { status: 403 });
    if (!this.state!.partner?.accepted) {
      return Response.json({ error: "NotReady", reason: "partner_not_accepted" }, { status: 409 });
    }
    // Normalize status if partner already accepted (fixes stale waiting_partner).
    if (this.state!.status === "waiting_partner") {
      this.state!.status = "ready";
    }
    if (this.state!.status !== "ready" && this.state!.status !== "searching") {
      return Response.json({ error: "NotReady", reason: this.state!.status }, { status: 409 });
    }
    // Allow re-start from ready only (not mid-search without tickets).
    if (this.state!.status === "searching" && this.state!.host.ticketId && this.state!.partner.ticketId) {
      return Response.json({ error: "AlreadySearching" }, { status: 409 });
    }
    this.state!.status = "ready";

    const hostTicket = (body.hostTicketId || "").trim();
    const partnerTicket = (body.partnerTicketId || "").trim() || this.state!.partner.ticketId || "";
    if (!hostTicket) {
      return Response.json({ error: "NeedTickets", need: "host" }, { status: 409 });
    }
    if (!partnerTicket) {
      return Response.json({ error: "NeedTickets", need: "partner" }, { status: 409 });
    }
    this.state!.host.ticketId = hostTicket;
    this.state!.partner.ticketId = partnerTicket;
    this.state!.status = "searching";

    const mmId = this.env.TEAM_MATCHMAKER.idFromName("globalTeam");
    const resp = await this.env.TEAM_MATCHMAKER.get(mmId).fetch(
      new Request("https://team/enqueue", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          lobbyId: this.state!.lobbyId,
          mode: this.state!.mode,
          stake: this.state!.stake,
          members: [
            {
              playerId: this.state!.host.pubkey,
              name: this.state!.host.name,
              ticketId: this.state!.host.ticketId,
              pieceStyleId: this.state!.host.pieceStyleId,
              diceStyleId: this.state!.host.diceStyleId,
            },
            {
              playerId: this.state!.partner.pubkey,
              name: this.state!.partner.name,
              ticketId: this.state!.partner.ticketId,
              pieceStyleId: this.state!.partner.pieceStyleId,
              diceStyleId: this.state!.partner.diceStyleId,
            },
          ],
        }),
      }),
    );
    const data = (await resp.json()) as { ok?: boolean; queuePosition?: number; error?: string };
    if (!resp.ok) {
      this.state!.status = "ready";
      return Response.json(data, { status: resp.status });
    }

    this.broadcast({ type: "Searching", payload: data });
    return Response.json({ ok: true, ...data });
  }

  private handleWs(request: Request, url: URL): Response {
    const playerId = (url.searchParams.get("playerId") || "").toLowerCase();
    const isMember =
      this.state!.host.pubkey === playerId ||
      (this.state!.partner?.pubkey === playerId && this.state!.partner.accepted);
    if (!isMember) return new Response("Forbidden", { status: 403 });

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    this.sockets.set(playerId, server);
    server.addEventListener("close", () => this.sockets.delete(playerId));
    server.send(JSON.stringify({ type: "Snapshot", payload: this.publicSnapshot() }));
    return new Response(null, { status: 101, webSocket: client });
  }

  private broadcast(msg: unknown) {
    const raw = JSON.stringify(msg);
    for (const ws of this.sockets.values()) {
      try {
        ws.send(raw);
      } catch {
        // ignore
      }
    }
  }

  private publicSnapshot() {
    const s = this.state!;
    return {
      lobbyId: s.lobbyId,
      teamCode: s.teamCode,
      mode: s.mode,
      stake: s.stake,
      status: s.status,
      host: {
        pubkey: s.host.pubkey,
        name: s.host.name,
        accepted: s.host.accepted,
        hasTicket: !!s.host.ticketId,
      },
      partner: s.partner
        ? {
            pubkey: s.partner.pubkey,
            name: s.partner.name,
            accepted: s.partner.accepted,
            hasTicket: !!s.partner.ticketId,
          }
        : null,
      matchId: s.matchId,
    };
  }

  private async hostToken(): Promise<string | null> {
    if (!this.state) return null;
    return mintTeamToken(this.env, {
      typ: "lobby_host",
      pid: this.state.host.pubkey,
      lid: this.state.lobbyId,
    });
  }
}
