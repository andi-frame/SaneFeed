import { Database } from "bun:sqlite";
import { createHmac } from "node:crypto";

export type Platform = "x" | "instagram";
export type CaseStatus = "pending" | "confirmed" | "rejected";
export type Vote = "agree" | "disagree";

export interface BuzzerEntry {
  platform: Platform;
  handle: string;
  reason: string | null;
}

export interface BuzzerList {
  version: number;
  updatedAt: string;
  entries: BuzzerEntry[];
}

export interface Proof {
  url: string;
  reason: string;
  createdAt: string;
}

export interface Case {
  id: number;
  platform: Platform;
  handle: string;
  status: CaseStatus;
  reporters: number;
  agree: number;
  disagree: number;
  priority: number;
  createdAt: string;
  updatedAt: string;
  proofs: Proof[];
  myVote: Vote | null;
}

export interface CasePage {
  cases: Case[];
  nextCursor: string | null;
}

export type ReportResult =
  | { kind: "created" | "appended"; caseId: number }
  | { kind: "conflict"; error: string };

export type VoteResult =
  | { kind: "ok"; agree: number; disagree: number; myVote: Vote }
  | { kind: "notFound" | "forbidden" | "conflict" };

export type DecisionResult = "ok" | "notFound" | "conflict";

const HANDLE_PATTERNS: Record<Platform, RegExp> = {
  x: /^[a-z0-9_]{1,15}$/,
  instagram: /^[a-z0-9._]{1,30}$/,
};

const REJECT_COOLDOWN_MS = 30 * 24 * 60 * 60 * 1000;

export function isPlatform(value: unknown): value is Platform {
  return value === "x" || value === "instagram";
}

/** Trim, strip one leading "@", lowercase; returns null when invalid for the platform. */
export function normalizeHandle(platform: Platform, raw: string): string | null {
  const handle = raw.trim().replace(/^@/, "").toLowerCase();
  return HANDLE_PATTERNS[platform].test(handle) ? handle : null;
}

/** Wilson score lower bound (z = 1.96) where reporters count as positive votes. */
export function wilsonPriority(reporters: number, agree: number, disagree: number): number {
  const n = reporters + agree + disagree;
  if (n === 0) return 0;
  const z2 = 1.96 * 1.96;
  const p = (reporters + agree) / n;
  return (p + z2 / (2 * n) - 1.96 * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n)) / (1 + z2 / n);
}

interface CaseRow {
  id: number;
  platform: Platform;
  handle: string;
  status: CaseStatus;
  reporters: number;
  agree: number;
  disagree: number;
  priority: number;
  n: number;
  createdAt: string;
  updatedAt: string;
}

type Cursor = [priority: number, n: number, createdAt: string, id: number];

const CASE_COLUMNS =
  "id, platform, handle, status, reporters, agree, disagree, priority, n, created_at AS createdAt, updated_at AS updatedAt";

// Each entry migrates user_version i -> i + 1.
const MIGRATIONS = [
  `CREATE TABLE IF NOT EXISTS buzzers (
     platform   TEXT NOT NULL,
     handle     TEXT NOT NULL,
     reason     TEXT,
     created_at TEXT NOT NULL,
     PRIMARY KEY (platform, handle)
   );
   CREATE TABLE IF NOT EXISTS reports (
     id         INTEGER PRIMARY KEY AUTOINCREMENT,
     platform   TEXT NOT NULL,
     handle     TEXT NOT NULL,
     reason     TEXT,
     created_at TEXT NOT NULL
   );
   CREATE TABLE IF NOT EXISTS meta (
     id         INTEGER PRIMARY KEY CHECK (id = 1),
     version    INTEGER NOT NULL,
     updated_at TEXT NOT NULL
   );
   INSERT OR IGNORE INTO meta (id, version, updated_at) VALUES (1, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));`,
  // Reports v2: cases with proofs and votes. v1 reports had no proofs or reporter identity, so they are dropped.
  `DROP TABLE reports;
   ALTER TABLE meta ADD COLUMN client_key TEXT;
   UPDATE meta SET client_key = hex(randomblob(32));
   CREATE TABLE cases (
     id         INTEGER PRIMARY KEY AUTOINCREMENT,
     platform   TEXT NOT NULL,
     handle     TEXT NOT NULL,
     status     TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'rejected')),
     reporters  INTEGER NOT NULL DEFAULT 0,
     agree      INTEGER NOT NULL DEFAULT 0,
     disagree   INTEGER NOT NULL DEFAULT 0,
     n          INTEGER NOT NULL DEFAULT 0,
     priority   REAL NOT NULL DEFAULT 0,
     note       TEXT,
     created_at TEXT NOT NULL,
     updated_at TEXT NOT NULL,
     decided_at TEXT
   );
   CREATE UNIQUE INDEX cases_one_pending ON cases (platform, handle) WHERE status = 'pending';
   CREATE INDEX cases_by_handle ON cases (platform, handle, decided_at);
   CREATE INDEX cases_queue ON cases (status, priority DESC, n DESC, created_at, id);
   CREATE TABLE case_reports (
     case_id     INTEGER NOT NULL REFERENCES cases (id),
     client_hash TEXT NOT NULL,
     reason      TEXT NOT NULL,
     created_at  TEXT NOT NULL,
     PRIMARY KEY (case_id, client_hash)
   );
   CREATE TABLE proofs (
     id          INTEGER PRIMARY KEY AUTOINCREMENT,
     case_id     INTEGER NOT NULL REFERENCES cases (id),
     client_hash TEXT NOT NULL,
     url         TEXT NOT NULL,
     reason      TEXT NOT NULL,
     created_at  TEXT NOT NULL
   );
   CREATE INDEX proofs_by_case ON proofs (case_id, id);
   CREATE TABLE votes (
     case_id     INTEGER NOT NULL REFERENCES cases (id),
     client_hash TEXT NOT NULL,
     vote        TEXT NOT NULL CHECK (vote IN ('agree', 'disagree')),
     created_at  TEXT NOT NULL,
     PRIMARY KEY (case_id, client_hash)
   );`,
];

export class Store {
  readonly db: Database;
  private readonly clientKey: string;

  constructor(path: string) {
    this.db = new Database(path, { create: true, strict: true });
    this.db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;");
    this.db.transaction(() => {
      const { user_version } = this.db.query<{ user_version: number }, []>("PRAGMA user_version").get()!;
      for (let v = user_version; v < MIGRATIONS.length; v++) this.db.exec(MIGRATIONS[v]!);
      this.db.exec(`PRAGMA user_version = ${MIGRATIONS.length}`);
    })();
    this.clientKey = this.db.query<{ k: string }, []>("SELECT client_key AS k FROM meta WHERE id = 1").get()!.k;
  }

  /** Keyed hash of a client id; raw ids are never stored. */
  hashClient(clientId: string): string {
    return createHmac("sha256", this.clientKey).update(clientId.toLowerCase()).digest("hex");
  }

  meta(): { version: number; updatedAt: string } {
    return this.db
      .query<{ version: number; updatedAt: string }, []>(
        "SELECT version, updated_at AS updatedAt FROM meta WHERE id = 1",
      )
      .get()!;
  }

  list(): BuzzerList {
    return this.db.transaction(() => ({
      ...this.meta(),
      entries: this.db
        .query<BuzzerEntry, []>("SELECT platform, handle, reason FROM buzzers ORDER BY platform, handle")
        .all(),
    }))();
  }

  private bump(): void {
    this.db.run("UPDATE meta SET version = version + 1, updated_at = ? WHERE id = 1", [new Date().toISOString()]);
  }

  /** Inserts or updates entries; bumps the version once if anything changed. Returns rows changed. */
  upsert(entries: BuzzerEntry[]): number {
    const stmt = this.db.query(
      `INSERT INTO buzzers (platform, handle, reason, created_at) VALUES ($platform, $handle, $reason, $now)
       ON CONFLICT (platform, handle) DO UPDATE SET reason = excluded.reason
       WHERE buzzers.reason IS NOT excluded.reason`,
    );
    return this.db.transaction(() => {
      const now = new Date().toISOString();
      let changed = 0;
      for (const e of entries) changed += stmt.run({ ...e, now }).changes;
      if (changed > 0) this.bump();
      return changed;
    })();
  }

  remove(platform: Platform, handle: string): boolean {
    return this.db.transaction(() => {
      const { changes } = this.db.run("DELETE FROM buzzers WHERE platform = ? AND handle = ?", [platform, handle]);
      if (changes > 0) this.bump();
      return changes > 0;
    })();
  }

  private recount(caseId: number, now: string): void {
    const c = this.db
      .query<{ reporters: number; agree: number; disagree: number }, { id: number }>(
        `SELECT (SELECT count(*) FROM case_reports WHERE case_id = $id) AS reporters,
                (SELECT count(*) FROM votes WHERE case_id = $id AND vote = 'agree') AS agree,
                (SELECT count(*) FROM votes WHERE case_id = $id AND vote = 'disagree') AS disagree`,
      )
      .get({ id: caseId })!;
    this.db.run(
      "UPDATE cases SET reporters = ?, agree = ?, disagree = ?, n = ?, priority = ?, updated_at = ? WHERE id = ?",
      [
        c.reporters,
        c.agree,
        c.disagree,
        c.reporters + c.agree + c.disagree,
        wilsonPriority(c.reporters, c.agree, c.disagree),
        now,
        caseId,
      ],
    );
  }

  fileReport(input: {
    platform: Platform;
    handle: string;
    clientHash: string;
    reason: string;
    proofs: string[];
  }): ReportResult {
    const { platform, handle, clientHash, reason, proofs } = input;
    return this.db.transaction((): ReportResult => {
      const now = new Date().toISOString();
      if (this.db.query("SELECT 1 FROM buzzers WHERE platform = ? AND handle = ?").get(platform, handle)) {
        return { kind: "conflict", error: "this account is already on the buzzer list" };
      }
      const cooldownStart = new Date(Date.now() - REJECT_COOLDOWN_MS).toISOString();
      const rejected = this.db
        .query("SELECT 1 FROM cases WHERE platform = ? AND handle = ? AND status = 'rejected' AND decided_at > ?")
        .get(platform, handle, cooldownStart);
      if (rejected) return { kind: "conflict", error: "a report for this account was rejected in the last 30 days" };

      let kind: "created" | "appended" = "appended";
      let caseId = this.db
        .query<{ id: number }, [string, string]>(
          "SELECT id FROM cases WHERE platform = ? AND handle = ? AND status = 'pending'",
        )
        .get(platform, handle)?.id;
      if (caseId === undefined) {
        kind = "created";
        caseId = Number(
          this.db.run("INSERT INTO cases (platform, handle, created_at, updated_at) VALUES (?, ?, ?, ?)", [
            platform,
            handle,
            now,
            now,
          ]).lastInsertRowid,
        );
      }
      this.db.run(
        "INSERT OR IGNORE INTO case_reports (case_id, client_hash, reason, created_at) VALUES (?, ?, ?, ?)",
        [caseId, clientHash, reason, now],
      );
      const insertProof = this.db.query(
        "INSERT INTO proofs (case_id, client_hash, url, reason, created_at) VALUES (?, ?, ?, ?, ?)",
      );
      for (const url of proofs) insertProof.run(caseId, clientHash, url, reason, now);
      // A reporter's earlier vote would double-count them.
      this.db.run("DELETE FROM votes WHERE case_id = ? AND client_hash = ?", [caseId, clientHash]);
      this.recount(caseId, now);
      return { kind, caseId };
    })();
  }

  vote(caseId: number, clientHash: string, vote: Vote): VoteResult {
    return this.db.transaction((): VoteResult => {
      const c = this.db.query<{ status: CaseStatus }, [number]>("SELECT status FROM cases WHERE id = ?").get(caseId);
      if (!c) return { kind: "notFound" };
      if (c.status !== "pending") return { kind: "conflict" };
      if (this.db.query("SELECT 1 FROM case_reports WHERE case_id = ? AND client_hash = ?").get(caseId, clientHash)) {
        return { kind: "forbidden" };
      }
      const now = new Date().toISOString();
      this.db.run(
        `INSERT INTO votes (case_id, client_hash, vote, created_at) VALUES (?, ?, ?, ?)
         ON CONFLICT (case_id, client_hash) DO UPDATE SET vote = excluded.vote`,
        [caseId, clientHash, vote, now],
      );
      this.recount(caseId, now);
      const { agree, disagree } = this.db
        .query<{ agree: number; disagree: number }, [number]>("SELECT agree, disagree FROM cases WHERE id = ?")
        .get(caseId)!;
      return { kind: "ok", agree, disagree, myVote: vote };
    })();
  }

  private hydrate(rows: CaseRow[], clientHash: string | null): Case[] {
    if (rows.length === 0) return [];
    const ids = JSON.stringify(rows.map((r) => r.id));
    const proofs = new Map<number, Proof[]>();
    for (const p of this.db
      .query<Proof & { caseId: number }, [string]>(
        `SELECT case_id AS caseId, url, reason, created_at AS createdAt FROM proofs
         WHERE case_id IN (SELECT value FROM json_each(?)) ORDER BY case_id, id`,
      )
      .all(ids)) {
      const { caseId, ...proof } = p;
      proofs.set(caseId, [...(proofs.get(caseId) ?? []), proof]);
    }
    const votes = new Map<number, Vote>();
    if (clientHash) {
      for (const v of this.db
        .query<{ caseId: number; vote: Vote }, [string, string]>(
          "SELECT case_id AS caseId, vote FROM votes WHERE client_hash = ? AND case_id IN (SELECT value FROM json_each(?))",
        )
        .all(clientHash, ids)) {
        votes.set(v.caseId, v.vote);
      }
    }
    return rows.map(({ n: _n, ...r }) => ({ ...r, proofs: proofs.get(r.id) ?? [], myVote: votes.get(r.id) ?? null }));
  }

  getCase(id: number, clientHash: string | null): Case | null {
    const row = this.db.query<CaseRow, [number]>(`SELECT ${CASE_COLUMNS} FROM cases WHERE id = ?`).get(id);
    return row ? this.hydrate([row], clientHash)[0]! : null;
  }

  /** Pending cases by priority desc, then n desc, then oldest first. `cursor` is opaque; throws on a malformed one. */
  pendingCases(opts: { platform?: Platform; limit: number; cursor?: string; clientHash: string | null }): CasePage {
    const after = opts.cursor === undefined ? null : decodeCursor(opts.cursor);
    const rows = this.db
      .query<CaseRow, Record<string, string | number | null>>(
        `SELECT ${CASE_COLUMNS} FROM cases
         WHERE status = 'pending' AND ($platform IS NULL OR platform = $platform)
           AND ($p IS NULL OR priority < $p
                OR (priority = $p AND (n < $n
                    OR (n = $n AND (created_at > $c OR (created_at = $c AND id > $id))))))
         ORDER BY priority DESC, n DESC, created_at, id
         LIMIT $limit`,
      )
      .all({
        platform: opts.platform ?? null,
        p: after?.[0] ?? null,
        n: after?.[1] ?? null,
        c: after?.[2] ?? null,
        id: after?.[3] ?? null,
        limit: opts.limit + 1,
      });
    const page = rows.slice(0, opts.limit);
    const last = page.at(-1);
    return {
      cases: this.hydrate(page, opts.clientHash),
      nextCursor:
        rows.length > opts.limit && last
          ? Buffer.from(JSON.stringify([last.priority, last.n, last.createdAt, last.id] satisfies Cursor)).toString(
              "base64url",
            )
          : null,
    };
  }

  decide(caseId: number, decision: "confirm" | "reject", note: string | null): DecisionResult {
    return this.db.transaction((): DecisionResult => {
      const c = this.db
        .query<{ platform: Platform; handle: string; status: CaseStatus }, [number]>(
          "SELECT platform, handle, status FROM cases WHERE id = ?",
        )
        .get(caseId);
      if (!c) return "notFound";
      if (c.status !== "pending") return "conflict";
      const now = new Date().toISOString();
      this.db.run("UPDATE cases SET status = ?, note = ?, decided_at = ?, updated_at = ? WHERE id = ?", [
        decision === "confirm" ? "confirmed" : "rejected",
        note,
        now,
        now,
        caseId,
      ]);
      if (decision === "confirm") {
        const firstReason = this.db
          .query<{ reason: string }, [number]>(
            "SELECT reason FROM case_reports WHERE case_id = ? ORDER BY created_at, rowid LIMIT 1",
          )
          .get(caseId)?.reason;
        // upsert opens a nested savepoint, so the list change and version bump commit with the decision.
        this.upsert([{ platform: c.platform, handle: c.handle, reason: note ?? firstReason ?? null }]);
      }
      return "ok";
    })();
  }

  close(): void {
    this.db.close();
  }
}

export class InvalidCursor extends Error {}

function decodeCursor(raw: string): Cursor {
  try {
    const v: unknown = JSON.parse(Buffer.from(raw, "base64url").toString());
    if (
      Array.isArray(v) &&
      v.length === 4 &&
      typeof v[0] === "number" &&
      Number.isInteger(v[1]) &&
      typeof v[2] === "string" &&
      Number.isInteger(v[3])
    ) {
      return v as Cursor;
    }
  } catch {}
  throw new InvalidCursor("invalid cursor");
}
