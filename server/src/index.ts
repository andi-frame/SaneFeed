import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { Store } from "./db";
import { createHandler } from "./handlers";

const adminToken = process.env.ADMIN_TOKEN;
if (!adminToken) {
  console.error("ADMIN_TOKEN is required. Set it to a long random secret, e.g. ADMIN_TOKEN=$(openssl rand -hex 32).");
  process.exit(1);
}

const port = Number(process.env.PORT ?? 8787);
const dbPath = process.env.DB_PATH ?? "./data/sanefeed.sqlite";
mkdirSync(dirname(dbPath), { recursive: true });

const handle = createHandler({ store: new Store(dbPath), adminToken });

const server = Bun.serve({
  port,
  maxRequestBodySize: 64 * 1024,
  fetch: (req, srv) => handle(req, (r) => srv.requestIP(r)?.address ?? null),
});

console.log(`SaneFeed buzzer server listening on :${server.port} (db: ${dbPath})`);
