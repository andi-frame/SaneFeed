import { Database } from "bun:sqlite";

export type Platform = "x" | "instagram";

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

export interface Report {
  id: number;
  platform: Platform;
  handle: string;
  reason: string | null;
  createdAt: string;
}

const HANDLE_PATTERNS: Record<Platform, RegExp> = {
  x: /^[a-z0-9_]{1,15}$/,
  instagram: /^[a-z0-9._]{1,30}$/,
};

export function isPlatform(value: unknown): value is Platform {
  return value === "x" || value === "instagram";
}

/** Trim, strip one leading "@", lowercase; returns null when invalid for the platform. */
export function normalizeHandle(platform: Platform, raw: string): string | null {
  const handle = raw.trim().replace(/^@/, "").toLowerCase();
  return HANDLE_PATTERNS[platform].test(handle) ? handle : null;
}

export class Store {
  readonly db: Database;

  constructor(path: string) {
    this.db = new Database(path, { create: true, strict: true });
    this.db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS buzzers (
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
      INSERT OR IGNORE INTO meta (id, version, updated_at) VALUES (1, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
    `);
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
    this.db.run("UPDATE meta SET version = version + 1, updated_at = ? WHERE id = 1", [
      new Date().toISOString(),
    ]);
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

  addReport(platform: Platform, handle: string, reason: string | null): void {
    this.db.run("INSERT INTO reports (platform, handle, reason, created_at) VALUES (?, ?, ?, ?)", [
      platform,
      handle,
      reason,
      new Date().toISOString(),
    ]);
  }

  reports(): Report[] {
    return this.db
      .query<Report, []>(
        "SELECT id, platform, handle, reason, created_at AS createdAt FROM reports ORDER BY id",
      )
      .all();
  }

  close(): void {
    this.db.close();
  }
}
