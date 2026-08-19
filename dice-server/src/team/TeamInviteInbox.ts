/**
 * Team invite inbox — one DO per pubkey. In-app invite delivery (client polls).
 * Never touches classic Matchmaker / GameRoom stacks.
 */
import { DurableObject } from "cloudflare:workers";
import type { Env } from "../index";

export type TeamInvite = {
  inviteId: string;
  lobbyId: string;
  teamCode: string;
  hostPubkey: string;
  hostName: string;
  mode: string;
  stake: number;
  createdAt: number;
  expiresAt: number;
};

type InboxState = {
  invites: TeamInvite[];
};

const MAX_INVITES = 20;
const INVITE_TTL_MS = 30 * 60 * 1000;

export class TeamInviteInbox extends DurableObject<Env> {
  private state: InboxState = { invites: [] };

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/pending") {
      this.prune();
      return Response.json({ ok: true, invites: this.state.invites });
    }

    if (request.method === "POST" && url.pathname === "/push") {
      const secret = request.headers.get("x-game-secret") || "";
      if (!this.env.GAME_WORKER_SECRET || secret !== this.env.GAME_WORKER_SECRET) {
        return Response.json({ error: "Forbidden" }, { status: 403 });
      }
      const body = (await request.json()) as TeamInvite;
      this.prune();
      if (this.state.invites.some((i) => i.inviteId === body.inviteId)) {
        return Response.json({ ok: true, duplicate: true });
      }
      this.state.invites.unshift(body);
      this.state.invites = this.state.invites.slice(0, MAX_INVITES);
      return Response.json({ ok: true });
    }

    if (request.method === "POST" && url.pathname === "/ack") {
      const body = (await request.json()) as { inviteId?: string };
      const id = (body.inviteId || "").trim();
      this.state.invites = this.state.invites.filter((i) => i.inviteId !== id);
      return Response.json({ ok: true });
    }

    return Response.json({ error: "NotFound" }, { status: 404 });
  }

  private prune() {
    const now = Date.now();
    this.state.invites = this.state.invites.filter((i) => i.expiresAt > now);
  }
}

export function inviteExpiresAt(now = Date.now()): number {
  return now + INVITE_TTL_MS;
}

export function randomTeamCode(): string {
  return String(Math.floor(100000 + Math.random() * 900000));
}
