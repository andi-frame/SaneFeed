// Usage: bun run src/seed.ts <file> [--platform x|instagram]
// File: one entry per line, either `handle` (needs --platform) or CSV `platform,handle[,reason]`.
// Lines starting with `#` and blank lines are skipped.
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { parseArgs } from "node:util";
import { isPlatform, normalizeHandle, Store, type BuzzerEntry } from "./db";

const { values, positionals } = parseArgs({
  args: Bun.argv.slice(2),
  options: { platform: { type: "string" } },
  allowPositionals: true,
});

const file = positionals[0];
const defaultPlatform = values.platform;
if (!file || (defaultPlatform !== undefined && !isPlatform(defaultPlatform))) {
  console.error("Usage: bun run src/seed.ts <file> [--platform x|instagram]");
  process.exit(1);
}

const entries: BuzzerEntry[] = [];
let skipped = 0;
const lines = (await Bun.file(file).text()).split(/\r?\n/);
lines.forEach((line, i) => {
  const trimmed = line.trim();
  if (trimmed === "" || trimmed.startsWith("#")) return;
  const cols = trimmed.split(",").map((c) => c.trim());
  const [platform, rawHandle, ...rest] = cols.length === 1 ? [defaultPlatform, cols[0]] : cols;
  const handle = isPlatform(platform) && rawHandle ? normalizeHandle(platform, rawHandle) : null;
  if (!isPlatform(platform) || handle === null) {
    console.warn(`line ${i + 1}: skipped "${trimmed}"`);
    skipped++;
    return;
  }
  const reason = rest.join(",").trim();
  entries.push({ platform, handle, reason: reason === "" ? null : reason });
});

const dbPath = process.env.DB_PATH ?? "./data/sanefeed.sqlite";
mkdirSync(dirname(dbPath), { recursive: true });
const store = new Store(dbPath);
const changed = store.upsert(entries);
console.log(`${changed} of ${entries.length} entries added or updated, ${skipped} skipped; list version ${store.meta().version}`);
store.close();
