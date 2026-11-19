import { createHash, timingSafeEqual } from "node:crypto";
import { InvalidCursor, isPlatform, normalizeHandle, type Platform, type Store } from "./db";

export interface HandlerOptions {
  store: Store;
  adminToken: string;
  /** Max report submissions per IP per window. */
  reportLimit?: number;
  /** Max votes per IP per window. */
  voteLimit?: number;
  rateWindowMs?: number;
  maxBodyBytes?: number;
  now?: () => number;
}

export type ClientIp = (req: Request) => string | null;
export type Handler = (req: Request, clientIp?: ClientIp) => Promise<Response>;

const MAX_BUZZER_REASON = 500;
const REPORT_REASON = { min: 10, max: 1000 };
const PROOFS = { min: 1, max: 5, maxUrlLength: 500 };
const PAGE = { default: 50, max: 100 };
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return Response.json(body, { status, headers });
}

const error = (status: number, message: string) => json({ error: message }, status);
const noContent = () => new Response(null, { status: 204 });

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, If-None-Match, X-Client-Id",
  "Access-Control-Expose-Headers": "ETag",
  "Access-Control-Max-Age": "86400",
};

function withCors(res: Response): Response {
  for (const [k, v] of Object.entries(cors)) res.headers.set(k, v);
  return res;
}

class BadRequest extends Error {}

async function readJson(req: Request, maxBytes: number): Promise<Record<string, unknown>> {
  const declared = Number(req.headers.get("content-length") ?? 0);
  if (declared > maxBytes) throw new BadRequest("body too large");
  const text = await req.text();
  if (Buffer.byteLength(text) > maxBytes) throw new BadRequest("body too large");
  if (text.trim() === "") return {};
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    throw new BadRequest("invalid JSON");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw new BadRequest("body must be a JSON object");
  }
  return body as Record<string, unknown>;
}

/** Optional trimmed string; empty becomes null. */
function optionalText(value: unknown, field: string, max: number): string | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") throw new BadRequest(`${field} must be a string`);
  const text = value.trim();
  if (text.length > max) throw new BadRequest(`${field} exceeds ${max} characters`);
  return text === "" ? null : text;
}

function parseReportReason(value: unknown): string {
  const reason = typeof value === "string" ? value.trim() : "";
  if (reason.length < REPORT_REASON.min || reason.length > REPORT_REASON.max) {
    throw new BadRequest(`reason must be ${REPORT_REASON.min} to ${REPORT_REASON.max} characters`);
  }
  return reason;
}

function parseProofs(value: unknown): string[] {
  if (!Array.isArray(value) || value.length < PROOFS.min || value.length > PROOFS.max) {
    throw new BadRequest(`proofs must be a list of ${PROOFS.min} to ${PROOFS.max} links`);
  }
  return value.map((raw) => {
    const url = typeof raw === "string" ? raw.trim() : "";
    if (url.length === 0 || url.length > PROOFS.maxUrlLength || URL.parse(url)?.protocol !== "https:") {
      throw new BadRequest(`each proof must be an https link of at most ${PROOFS.maxUrlLength} characters`);
    }
    return url;
  });
}

function parseTarget(platform: unknown, handle: unknown): { platform: Platform; handle: string } {
  if (!isPlatform(platform)) throw new BadRequest('platform must be "x" or "instagram"');
  if (typeof handle !== "string") throw new BadRequest("handle must be a string");
  const normalized = normalizeHandle(platform, handle);
  if (normalized === null) throw new BadRequest(`invalid ${platform} handle`);
  return { platform, handle: normalized };
}

function parseId(raw: string | undefined): number | null {
  return raw !== undefined && /^[1-9]\d{0,15}$/.test(raw) ? Number(raw) : null;
}

function parseLimit(raw: string | null): number {
  if (raw === null) return PAGE.default;
  const limit = Number(raw);
  if (!Number.isInteger(limit) || limit < 1 || limit > PAGE.max) {
    throw new BadRequest(`limit must be an integer from 1 to ${PAGE.max}`);
  }
  return limit;
}

const sha256 = (s: string) => createHash("sha256").update(s).digest();

// Hashing to fixed length keeps the compare constant-time regardless of input length.
function tokenMatches(header: string | null, token: string): boolean {
  if (!header?.startsWith("Bearer ")) return false;
  return timingSafeEqual(sha256(header.slice(7)), sha256(token));
}

/** Fixed-window per-key counter. */
function rateLimiter(limit: number, windowMs: number, now: () => number) {
  const hits = new Map<string, { start: number; count: number }>();
  return (key: string): boolean => {
    const t = now();
    if (hits.size > 10_000) {
      for (const [k, v] of hits) if (t - v.start >= windowMs) hits.delete(k);
    }
    const entry = hits.get(key);
    if (!entry || t - entry.start >= windowMs) {
      hits.set(key, { start: t, count: 1 });
      return true;
    }
    return ++entry.count <= limit;
  };
}

/** Builds the fetch handler. `clientIp` resolves the caller address (Bun's server.requestIP in production). */
export function createHandler(opts: HandlerOptions): Handler {
  const { store, adminToken } = opts;
  const maxBody = opts.maxBodyBytes ?? 8192;
  const windowMs = opts.rateWindowMs ?? 60_000;
  const now = opts.now ?? Date.now;
  const allowReport = rateLimiter(opts.reportLimit ?? 10, windowMs, now);
  const allowVote = rateLimiter(opts.voteLimit ?? 60, windowMs, now);

  /** Hashed X-Client-Id; null when absent and not required. */
  function clientHash(req: Request, required: true): string;
  function clientHash(req: Request, required: false): string | null;
  function clientHash(req: Request, required: boolean): string | null {
    const id = req.headers.get("x-client-id");
    if (id === null && !required) return null;
    if (id === null || !UUID_V4.test(id)) throw new BadRequest("X-Client-Id header must be a UUID v4");
    return store.hashClient(id);
  }

  function casePage(req: Request, url: URL, platform?: Platform): Response {
    return json(
      store.pendingCases({
        platform,
        limit: parseLimit(url.searchParams.get("limit")),
        cursor: url.searchParams.get("cursor") ?? undefined,
        clientHash: clientHash(req, false),
      }),
    );
  }

  async function route(req: Request, clientIp: ClientIp): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "");
    const parts = path.split("/").slice(1);
    const method = req.method;
    const ip = clientIp(req) ?? "unknown";

    if (path === "/healthz") return new Response("ok");

    if (path === "/v1/buzzers") {
      if (method !== "GET") return error(405, "method not allowed");
      const list = store.list();
      const etag = `"${list.version}"`;
      const headers = { ETag: etag, "Cache-Control": "public, max-age=300" };
      if (req.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers });
      return json(list, 200, headers);
    }

    if (path === "/v1/reports") {
      if (method === "GET") {
        const platform = url.searchParams.get("platform");
        if (platform !== null && !isPlatform(platform)) throw new BadRequest('platform must be "x" or "instagram"');
        return casePage(req, url, platform ?? undefined);
      }
      if (method !== "POST") return error(405, "method not allowed");
      const reporter = clientHash(req, true);
      if (!allowReport(ip)) return error(429, "too many reports, try again later");
      const body = await readJson(req, maxBody);
      const result = store.fileReport({
        ...parseTarget(body.platform, body.handle),
        clientHash: reporter,
        reason: parseReportReason(body.reason),
        proofs: parseProofs(body.proofs),
      });
      if (result.kind === "conflict") return error(409, result.error);
      return json({ caseId: result.caseId, status: "pending" }, result.kind === "created" ? 201 : 200);
    }

    if (parts[1] === "reports" && parts.length <= 4) {
      const id = parseId(parts[2]);
      if (id === null) return error(404, "not found");
      if (parts.length === 3) {
        if (method !== "GET") return error(405, "method not allowed");
        const found = store.getCase(id, clientHash(req, false));
        return found ? json(found) : error(404, "case not found");
      }
      if (parts[3] !== "votes") return error(404, "not found");
      if (method !== "POST") return error(405, "method not allowed");
      const voter = clientHash(req, true);
      if (!allowVote(ip)) return error(429, "too many votes, try again later");
      const { vote } = await readJson(req, maxBody);
      if (vote !== "agree" && vote !== "disagree") throw new BadRequest('vote must be "agree" or "disagree"');
      const result = store.vote(id, voter, vote);
      switch (result.kind) {
        case "ok":
          return json({ agree: result.agree, disagree: result.disagree, myVote: result.myVote });
        case "notFound":
          return error(404, "case not found");
        case "forbidden":
          return error(403, "you reported this account, so you cannot vote on it");
        case "conflict":
          return error(409, "this case has already been decided");
      }
    }

    const isAdminRoute = parts[1] === "admin" || (parts[1] === "buzzers" && parts.length > 2);
    if (!isAdminRoute) return error(404, "not found");
    if (!tokenMatches(req.headers.get("authorization"), adminToken)) return error(401, "unauthorized");

    if (path === "/v1/admin/queue") {
      return method === "GET" ? casePage(req, url) : error(405, "method not allowed");
    }

    if (parts[1] === "admin" && parts[2] === "cases" && parts[4] === "decision" && parts.length === 5) {
      const id = parseId(parts[3]);
      if (id === null) return error(404, "not found");
      if (method !== "POST") return error(405, "method not allowed");
      const body = await readJson(req, maxBody);
      if (body.decision !== "confirm" && body.decision !== "reject") {
        throw new BadRequest('decision must be "confirm" or "reject"');
      }
      const result = store.decide(id, body.decision, optionalText(body.note, "note", MAX_BUZZER_REASON));
      if (result === "notFound") return error(404, "case not found");
      if (result === "conflict") return error(409, "this case has already been decided");
      return noContent();
    }

    if (parts[1] !== "buzzers" || parts.length !== 4) return error(404, "not found");
    const { platform, handle } = parseTarget(parts[2], decodeURIComponent(parts[3]!));

    if (method === "PUT") {
      const reason = optionalText((await readJson(req, maxBody)).reason, "reason", MAX_BUZZER_REASON);
      store.upsert([{ platform, handle, reason }]);
      return noContent();
    }
    if (method === "DELETE") {
      return store.remove(platform, handle) ? noContent() : error(404, "not found");
    }
    return error(405, "method not allowed");
  }

  return async function fetch(req: Request, clientIp: ClientIp = () => null): Promise<Response> {
    const { pathname } = new URL(req.url);
    const publicRoute = pathname === "/v1/buzzers" || pathname === "/v1/reports" || pathname.startsWith("/v1/reports/");
    if (req.method === "OPTIONS") {
      return publicRoute ? withCors(noContent()) : new Response(null, { status: 404 });
    }
    let res: Response;
    try {
      res = await route(req, clientIp);
    } catch (e) {
      if (e instanceof BadRequest || e instanceof InvalidCursor) res = error(400, e.message);
      else if (e instanceof URIError) res = error(400, "malformed path");
      else {
        console.error(e);
        res = error(500, "internal error");
      }
    }
    return publicRoute ? withCors(res) : res;
  };
}
