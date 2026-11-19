import { Database } from "bun:sqlite";
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { type Case, type CasePage, Store, wilsonPriority } from "../src/db";
import { createHandler, type Handler } from "../src/handlers";

const TOKEN = "test-token";
const auth = { Authorization: `Bearer ${TOKEN}` };
let store: Store;
let handle: Handler;

beforeEach(() => {
  store = new Store(":memory:");
  handle = createHandler({ store, adminToken: TOKEN });
});
afterEach(() => store.close());

const client = () => crypto.randomUUID();
const req = (path: string, init?: RequestInit) =>
  handle(new Request(`http://localhost${path}`, init), () => "1.2.3.4");

const report = (clientId: string | null, body: Record<string, unknown> = {}) =>
  req("/v1/reports", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(clientId ? { "X-Client-Id": clientId } : {}) },
    body: JSON.stringify({
      platform: "x",
      handle: "budi83749201",
      reason: "posts identical talking points every hour",
      proofs: ["https://x.com/budi83749201/status/1"],
      ...body,
    }),
  });

async function openCase(clientId: string, body: Record<string, unknown> = {}): Promise<number> {
  const res = await report(clientId, body);
  expect(res.status).toBeOneOf([200, 201]);
  const created = (await res.json()) as { caseId: number };
  return created.caseId;
}

const vote = (caseId: number, clientId: string | null, v: string) =>
  req(`/v1/reports/${caseId}/votes`, {
    method: "POST",
    headers: clientId ? { "X-Client-Id": clientId } : {},
    body: JSON.stringify({ vote: v }),
  });

const getCase = async (id: number, clientId?: string) =>
  (await (await req(`/v1/reports/${id}`, { headers: clientId ? { "X-Client-Id": clientId } : {} })).json()) as Case;

const decide = (id: number, body: Record<string, unknown>) =>
  req(`/v1/admin/cases/${id}/decision`, { method: "POST", headers: auth, body: JSON.stringify(body) });

describe("POST /v1/reports", () => {
  test("201 opens a case, 200 appends to the pending case; reporters are not double-counted", async () => {
    const alice = client();
    const first = await report(alice);
    expect(first.status).toBe(201);
    const { caseId } = (await first.json()) as { caseId: number };
    expect(first.headers.get("Access-Control-Allow-Origin")).toBe("*");

    const again = await report(alice, { handle: "@Budi83749201", proofs: ["https://archive.org/x"] });
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ caseId, status: "pending" });

    const bob = await report(client());
    expect(bob.status).toBe(200);

    const c = await getCase(caseId);
    expect(c).toMatchObject({ status: "pending", handle: "budi83749201", reporters: 2, agree: 0, disagree: 0 });
    expect(c.proofs.map((p) => p.url)).toEqual([
      "https://x.com/budi83749201/status/1",
      "https://archive.org/x",
      "https://x.com/budi83749201/status/1",
    ]);
    expect(c.priority).toBeCloseTo(wilsonPriority(2, 0, 0));
    expect(JSON.stringify(c)).not.toContain(alice);
  });

  test("missing or invalid X-Client-Id is 400", async () => {
    expect((await report(null)).status).toBe(400);
    expect((await report("not-a-uuid")).status).toBe(400);
    const caseId = await openCase(client());
    expect((await vote(caseId, null, "agree")).status).toBe(400);
  });

  test("validates reason and proofs", async () => {
    const bad = [
      { reason: "short" },
      { reason: "a".repeat(1001) },
      { proofs: [] },
      { proofs: ["http://x.com/a"] },
      { proofs: ["not a url"] },
      { proofs: Array(6).fill("https://x.com/a") },
      { proofs: [`https://x.com/${"a".repeat(500)}`] },
      { platform: "x", handle: "has-dash" },
    ];
    for (const body of bad) expect((await report(client(), body)).status).toBe(400);
  });

  test("409 when already listed", async () => {
    await req("/v1/buzzers/x/budi83749201", { method: "PUT", headers: auth });
    expect((await report(client())).status).toBe(409);
  });

  test("rate limited per IP", async () => {
    handle = createHandler({ store, adminToken: TOKEN, reportLimit: 2 });
    expect((await report(client())).status).toBe(201);
    expect((await report(client())).status).toBe(200);
    expect((await report(client())).status).toBe(429);
  });
});

describe("votes", () => {
  test("upsert changes the vote; reporters cannot vote", async () => {
    const reporter = client();
    const caseId = await openCase(reporter);
    const voter = client();

    let res = await vote(caseId, voter, "agree");
    expect(await res.json()).toEqual({ agree: 1, disagree: 0, myVote: "agree" });
    res = await vote(caseId, voter, "disagree");
    expect(await res.json()).toEqual({ agree: 0, disagree: 1, myVote: "disagree" });
    expect((await getCase(caseId, voter)).myVote).toBe("disagree");
    expect((await getCase(caseId)).myVote).toBeNull();
    expect((await getCase(caseId)).priority).toBeCloseTo(wilsonPriority(1, 0, 1));

    expect((await vote(caseId, reporter, "agree")).status).toBe(403);
    expect((await vote(caseId, voter, "maybe")).status).toBe(400);
    expect((await vote(999, voter, "agree")).status).toBe(404);
  });

  test("a voter who later reports loses the vote instead of counting twice", async () => {
    const caseId = await openCase(client());
    const carol = client();
    await vote(caseId, carol, "agree");
    await report(carol);
    expect(await getCase(caseId)).toMatchObject({ reporters: 2, agree: 0 });
  });
});

describe("queue", () => {
  test("pending cases ordered by priority with cursor pagination", async () => {
    // weak: 1 reporter, 2 disagree. strong: 3 reporters. mid: 1 reporter.
    const weak = await openCase(client(), { handle: "weak" });
    await vote(weak, client(), "disagree");
    await vote(weak, client(), "disagree");
    for (let i = 0; i < 3; i++) await report(client(), { handle: "strong" });
    await report(client(), { handle: "mid", platform: "instagram" });

    const page1 = (await (await req("/v1/reports?limit=2")).json()) as CasePage;
    expect(page1.cases.map((c) => c.handle)).toEqual(["strong", "mid"]);
    expect(page1.nextCursor).not.toBeNull();
    const page2 = (await (await req(`/v1/reports?limit=2&cursor=${page1.nextCursor}`)).json()) as CasePage;
    expect(page2.cases.map((c) => c.handle)).toEqual(["weak"]);
    expect(page2.nextCursor).toBeNull();

    const ig = (await (await req("/v1/reports?platform=instagram")).json()) as CasePage;
    expect(ig.cases.map((c) => c.handle)).toEqual(["mid"]);

    const admin = (await (await req("/v1/admin/queue", { headers: auth })).json()) as CasePage;
    expect(admin.cases.map((c) => c.handle)).toEqual(["strong", "mid", "weak"]);
    expect(admin.cases[0]!.proofs).toHaveLength(3);

    expect((await req("/v1/reports?cursor=garbage")).status).toBe(400);
    expect((await req("/v1/reports?limit=0")).status).toBe(400);
  });

  test("ties break by larger n, then older case", async () => {
    // Same priority (all reporters, no votes) needs same n; equal n -> older first.
    await report(client(), { handle: "older" });
    await report(client(), { handle: "newer" });
    const page = (await (await req("/v1/reports")).json()) as CasePage;
    expect(page.cases.map((c) => c.handle)).toEqual(["older", "newer"]);
  });
});

describe("decisions", () => {
  test("confirm adds to the list and bumps the version atomically", async () => {
    const caseId = await openCase(client());
    expect((await decide(caseId, { decision: "confirm" })).status).toBe(204);

    const list = (await (await req("/v1/buzzers")).json()) as { version: number; entries: unknown[] };
    expect(list.version).toBe(1);
    expect(list.entries).toEqual([
      { platform: "x", handle: "budi83749201", reason: "posts identical talking points every hour" },
    ]);
    expect((await getCase(caseId)).status).toBe("confirmed");
    expect((await decide(caseId, { decision: "reject" })).status).toBe(409);
    expect((await vote(caseId, client(), "agree")).status).toBe(409);
    expect((await report(client())).status).toBe(409);
  });

  test("confirm rolls back when the list write fails", async () => {
    const caseId = await openCase(client());
    store.db.exec(`CREATE TRIGGER fail BEFORE INSERT ON buzzers BEGIN SELECT RAISE(ABORT, 'boom'); END`);
    expect(() => store.decide(caseId, "confirm", null)).toThrow("boom");
    expect((await getCase(caseId)).status).toBe("pending");
    expect(store.meta().version).toBe(0);
  });

  test("confirm uses the note as reason when given", async () => {
    const caseId = await openCase(client());
    await decide(caseId, { decision: "confirm", note: "verified network" });
    expect(store.list().entries[0]!.reason).toBe("verified network");
  });

  test("reject blocks re-reports for 30 days", async () => {
    const caseId = await openCase(client());
    expect((await decide(caseId, { decision: "reject" })).status).toBe(204);
    expect((await getCase(caseId)).status).toBe("rejected");
    expect(store.list().entries).toEqual([]);
    expect((await report(client())).status).toBe(409);

    const old = new Date(Date.now() - 31 * 24 * 60 * 60 * 1000).toISOString();
    store.db.run("UPDATE cases SET decided_at = ? WHERE id = ?", [old, caseId]);
    expect((await report(client())).status).toBe(201);
  });

  test("400 on bad decision, 404 on unknown case", async () => {
    const caseId = await openCase(client());
    expect((await decide(caseId, { decision: "maybe" })).status).toBe(400);
    expect((await decide(999, { decision: "confirm" })).status).toBe(404);
  });
});

test("migrates a v1 database", () => {
  const path = join(tmpdir(), `sanefeed-migrate-${crypto.randomUUID()}.sqlite`);
  const v1 = new Database(path);
  v1.exec(`CREATE TABLE buzzers (platform TEXT NOT NULL, handle TEXT NOT NULL, reason TEXT, created_at TEXT NOT NULL, PRIMARY KEY (platform, handle));
           CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, handle TEXT NOT NULL, reason TEXT, created_at TEXT NOT NULL);
           CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL, updated_at TEXT NOT NULL);
           INSERT INTO meta VALUES (1, 4, '2026-01-01T00:00:00.000Z');
           INSERT INTO buzzers VALUES ('x', 'kept', NULL, '2026-01-01T00:00:00.000Z');`);
  v1.close();
  const migrated = new Store(path);
  expect(migrated.list()).toMatchObject({ version: 4, entries: [{ handle: "kept" }] });
  expect(migrated.fileReport({ platform: "x", handle: "new", clientHash: "h", reason: "r", proofs: ["https://a"] }).kind).toBe(
    "created",
  );
  migrated.close();
  rmSync(path, { force: true });
  rmSync(`${path}-wal`, { force: true });
  rmSync(`${path}-shm`, { force: true });
});

test("CORS preflight allows X-Client-Id on report routes", async () => {
  for (const path of ["/v1/reports", "/v1/reports/1", "/v1/reports/1/votes"]) {
    const res = await req(path, { method: "OPTIONS" });
    expect(res.status).toBe(204);
    expect(res.headers.get("Access-Control-Allow-Headers")).toContain("X-Client-Id");
  }
  expect((await req("/v1/admin/queue", { method: "OPTIONS" })).status).toBe(404);
});
