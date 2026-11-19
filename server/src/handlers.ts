import { createHash, timingSafeEqual } from "node:crypto";
import { isPlatform, normalizeHandle, type Platform, type Store } from "./db";

export interface HandlerOptions {
  store: Store;
  adminToken: string;
  /** Max reports per IP per window. */
  reportLimit?: number;
  reportWindowMs?: number;
  maxBodyBytes?: number;
  now?: () => number;
}

export type ClientIp = (req: Request) => string | null;
export type Handler = (req: Request, clientIp?: ClientIp) => Promise<Response>;

const MAX_REASON_LENGTH = 500;

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return Response.json(body, { status, headers });
}

const error = (status: number, message: string) => json({ error: message }, status);

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, If-None-Match",
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

function parseReason(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") throw new BadRequest("reason must be a string");
  const reason = value.trim();
  if (reason.length > MAX_REASON_LENGTH) throw new BadRequest(`reason exceeds ${MAX_REASON_LENGTH} characters`);
  return reason === "" ? null : reason;
}

function parseTarget(platform: unknown, handle: unknown): { platform: Platform; handle: string } {
  if (!isPlatform(platform)) throw new BadRequest('platform must be "x" or "instagram"');
  if (typeof handle !== "string") throw new BadRequest("handle must be a string");
  const normalized = normalizeHandle(platform, handle);
  if (normalized === null) throw new BadRequest(`invalid ${platform} handle`);
  return { platform, handle: normalized };
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
  const maxBody = opts.maxBodyBytes ?? 4096;
  const allowReport = rateLimiter(opts.reportLimit ?? 10, opts.reportWindowMs ?? 60_000, opts.now ?? Date.now);

  async function route(req: Request, clientIp: ClientIp): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "");
    const method = req.method;

    if (path === "/v1/buzzers" && method === "GET") {
      const list = store.list();
      const etag = `"${list.version}"`;
      const headers = { ETag: etag, "Cache-Control": "public, max-age=300" };
      if (req.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers });
      return json(list, 200, headers);
    }

    if (path === "/v1/reports" && method === "POST") {
      if (!allowReport(clientIp(req) ?? "unknown")) return error(429, "too many reports, try again later");
      const body = await readJson(req, maxBody);
      const { platform, handle } = parseTarget(body.platform, body.handle);
      store.addReport(platform, handle, parseReason(body.reason));
      return json({ status: "received" }, 202);
    }

    const isAdminRoute = path === "/v1/reports" || path.startsWith("/v1/buzzers/");
    if (!isAdminRoute) return error(404, "not found");
    if (!tokenMatches(req.headers.get("authorization"), adminToken)) return error(401, "unauthorized");

    if (path === "/v1/reports") {
      return method === "GET" ? json({ reports: store.reports() }) : error(405, "method not allowed");
    }

    const parts = path.split("/");
    if (parts.length !== 5) return error(404, "not found");
    const { platform, handle } = parseTarget(parts[3], decodeURIComponent(parts[4]!));

    if (method === "PUT") {
      const reason = parseReason((await readJson(req, maxBody)).reason);
      store.upsert([{ platform, handle, reason }]);
      return new Response(null, { status: 204 });
    }
    if (method === "DELETE") {
      return store.remove(platform, handle) ? new Response(null, { status: 204 }) : error(404, "not found");
    }
    return error(405, "method not allowed");
  }

  return async function fetch(req: Request, clientIp: ClientIp = () => null): Promise<Response> {
    const url = new URL(req.url);
    const publicRoute =
      url.pathname === "/v1/buzzers" || (url.pathname === "/v1/reports" && req.method !== "GET");
    if (req.method === "OPTIONS") {
      return publicRoute ? withCors(new Response(null, { status: 204 })) : new Response(null, { status: 404 });
    }
    let res: Response;
    try {
      res = await route(req, clientIp);
    } catch (e) {
      if (e instanceof BadRequest) res = error(400, e.message);
      else if (e instanceof URIError) res = error(400, "malformed path");
      else {
        console.error(e);
        res = error(500, "internal error");
      }
    }
    return publicRoute ? withCors(res) : res;
  };
}
