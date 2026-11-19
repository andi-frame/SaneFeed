import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { Store } from "../src/db";
import { createHandler, type Handler } from "../src/handlers";

const TOKEN = "test-token";
const BASE = "http://localhost";
let store: Store;
let handle: Handler;

beforeEach(() => {
  store = new Store(":memory:");
  handle = createHandler({ store, adminToken: TOKEN });
});
afterEach(() => store.close());

const auth = { Authorization: `Bearer ${TOKEN}` };
const req = (path: string, init?: RequestInit) => handle(new Request(BASE + path, init), () => "1.2.3.4");
const list = async (): Promise<any> => (await req("/v1/buzzers")).json();

describe("GET /v1/buzzers", () => {
  test("returns list with ETag and cache headers, 304 on match", async () => {
    const res = await req("/v1/buzzers");
    expect(res.status).toBe(200);
    expect(res.headers.get("ETag")).toBe('"0"');
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=300");
    expect(res.headers.get("Access-Control-Allow-Origin")).toBe("*");
    const body = (await res.json()) as { updatedAt: string };
    expect(body).toMatchObject({ version: 0, entries: [] });
    expect(new Date(body.updatedAt).toISOString()).toBe(body.updatedAt);

    const cached = await req("/v1/buzzers", { headers: { "If-None-Match": '"0"' } });
    expect(cached.status).toBe(304);
    expect(await cached.text()).toBe("");

    const stale = await req("/v1/buzzers", { headers: { "If-None-Match": '"7"' } });
    expect(stale.status).toBe(200);
  });
});

describe("admin", () => {
  test("PUT and DELETE bump the version", async () => {
    let res = await req("/v1/buzzers/x/@Budi83749201", {
      method: "PUT",
      headers: auth,
      body: JSON.stringify({ reason: "coordinated" }),
    });
    expect(res.status).toBe(204);
    expect(await list()).toMatchObject({
      version: 1,
      entries: [{ platform: "x", handle: "budi83749201", reason: "coordinated" }],
    });

    res = await req("/v1/buzzers/instagram/some.user", { method: "PUT", headers: auth });
    expect(res.status).toBe(204);
    expect((await list()).version).toBe(2);

    res = await req("/v1/buzzers/x/budi83749201", { method: "DELETE", headers: auth });
    expect(res.status).toBe(204);
    const after = await list();
    expect(after.version).toBe(3);
    expect(after.entries).toEqual([{ platform: "instagram", handle: "some.user", reason: null }]);

    res = await req("/v1/buzzers/x/budi83749201", { method: "DELETE", headers: auth });
    expect(res.status).toBe(404);
    expect((await list()).version).toBe(3);
  });

  test("401 without or with wrong token", async () => {
    const variants: Record<string, string>[] = [{}, { Authorization: "Bearer nope" }, { Authorization: TOKEN }];
    for (const headers of variants) {
      expect((await req("/v1/buzzers/x/abc", { method: "PUT", headers })).status).toBe(401);
      expect((await req("/v1/buzzers/x/abc", { method: "DELETE", headers })).status).toBe(401);
      expect((await req("/v1/reports", { headers })).status).toBe(401);
    }
    expect((await list()).version).toBe(0);
  });

  test("400 on invalid platform or handle", async () => {
    const bad = ["/v1/buzzers/tiktok/abc", "/v1/buzzers/x/has.dot", "/v1/buzzers/x/abcdefghijklmnop", "/v1/buzzers/instagram/no-dash"];
    for (const path of bad) {
      const res = await req(path, { method: "PUT", headers: auth });
      expect(res.status).toBe(400);
      expect(await res.json()).toHaveProperty("error");
    }
    const badBody = await req("/v1/buzzers/x/abc", { method: "PUT", headers: auth, body: "{" });
    expect(badBody.status).toBe(400);
  });
});

describe("POST /v1/reports", () => {
  const post = (body: unknown) =>
    req("/v1/reports", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

  test("accepted, stored as pending, not listed", async () => {
    const res = await post({ platform: "x", handle: "  @SomeBuzzer ", reason: "spam" });
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ status: "received" });
    expect(res.headers.get("Access-Control-Allow-Origin")).toBe("*");
    expect(await list()).toMatchObject({ version: 0, entries: [] });

    const pending = (await (await req("/v1/reports", { headers: auth })).json()) as { reports: unknown[] };
    expect(pending.reports).toHaveLength(1);
    expect(pending.reports[0]).toMatchObject({ platform: "x", handle: "somebuzzer", reason: "spam" });
  });

  test("validates input", async () => {
    for (const body of [{}, { platform: "x" }, { platform: "fb", handle: "a" }, { platform: "x", handle: "a b" }, { platform: "x", handle: "a", reason: 5 }, [1]]) {
      expect((await post(body)).status).toBe(400);
    }
    const huge = await post({ platform: "x", handle: "abc", reason: "a".repeat(10_000) });
    expect(huge.status).toBe(400);
  });

  test("rate limits per IP", async () => {
    handle = createHandler({ store, adminToken: TOKEN, reportLimit: 3 });
    for (let i = 0; i < 3; i++) expect((await post({ platform: "x", handle: "abc" })).status).toBe(202);
    expect((await post({ platform: "x", handle: "abc" })).status).toBe(429);
  });

  test("CORS preflight", async () => {
    const res = await req("/v1/reports", { method: "OPTIONS" });
    expect(res.status).toBe(204);
    expect(res.headers.get("Access-Control-Allow-Methods")).toContain("POST");
  });
});
