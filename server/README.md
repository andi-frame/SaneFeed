# SaneFeed buzzer server

Serves the shared list of buzzer accounts (paid or coordinated propaganda accounts) that SaneFeed clients hide, and runs the community review queue: users report accounts with proof links, others vote, and an admin confirms or rejects. Bun + built-in SQLite, no runtime dependencies.

## Run

```sh
bun install            # dev types only
ADMIN_TOKEN=$(openssl rand -hex 32) bun start
bun test
```

Docker:

```sh
docker build -t sanefeed-server .
docker run -p 8787:8787 -e ADMIN_TOKEN=... -v sanefeed-data:/app/data sanefeed-server
```

### Environment

| Variable      | Default                   | Notes                                   |
| ------------- | ------------------------- | --------------------------------------- |
| `ADMIN_TOKEN` | none (required)           | Server exits on start if missing.       |
| `PORT`        | `8787`                    |                                         |
| `DB_PATH`     | `./data/sanefeed.sqlite`  | Directory is created if missing.        |

### Seeding

```sh
bun run seed handles.csv                     # lines: platform,handle[,reason]
bun run seed handles.txt --platform x        # lines: handle
```

Blank lines and lines starting with `#` are skipped; invalid handles are reported and skipped. The list version is bumped once per import if anything changed.

## API

Handles are normalized everywhere: trimmed, one leading `@` removed, lowercased. Valid: x `^[a-z0-9_]{1,15}$`, instagram `^[a-z0-9._]{1,30}$`. Invalid input returns `400 {"error": "..."}`.

### Buzzer list

| Method   | Path                               | Auth  | Result |
| -------- | ---------------------------------- | ----- | ------ |
| `GET`    | `/v1/buzzers`                      | none  | `200 {"version", "updatedAt", "entries": [{"platform", "handle", "reason"}]}` with `ETag: "<version>"`, `Cache-Control: public, max-age=300`. `304` when `If-None-Match` matches. |
| `PUT`    | `/v1/buzzers/{platform}/{handle}`  | admin | Body `{"reason"?}` (optional) → `204`. |
| `DELETE` | `/v1/buzzers/{platform}/{handle}`  | admin | `204`, or `404` if not listed. |

Every change to the list increments `version` in the same transaction as the write; a `PUT` that changes nothing leaves it unchanged.

### Reports and review

One **case** per account; reports with proofs accumulate in the pending case. Report and vote requests must send `X-Client-Id: <UUID v4>` (generated once per install), otherwise `400`. Client ids are stored only as a keyed HMAC and never returned.

| Method | Path                                   | Auth  | Result |
| ------ | -------------------------------------- | ----- | ------ |
| `POST` | `/v1/reports`                          | none  | Body `{"platform", "handle", "reason": 10–1000 chars, "proofs": [1–5 https URLs, ≤500 chars each]}` → `201 {"caseId", "status": "pending"}` for a new case, `200` same shape when added to the pending case. `409` if the account is already listed or its case was rejected less than 30 days ago. Reporting the same case again adds proofs without counting the reporter twice. |
| `GET`  | `/v1/reports?platform=&limit=&cursor=` | none  | `200 {"cases": [Case], "nextCursor": string\|null}`, pending only, highest priority first. `limit` 1–100, default 50. |
| `GET`  | `/v1/reports/{caseId}`                 | none  | `200 Case` (any status) or `404`. |
| `POST` | `/v1/reports/{caseId}/votes`           | none  | Body `{"vote": "agree"\|"disagree"}` → `200 {"agree", "disagree", "myVote"}`. One vote per client; voting again replaces it. `403` for the case's reporters, `409` once decided. |
| `GET`  | `/v1/admin/queue?limit=&cursor=`       | admin | Same as the public list, all platforms. |
| `POST` | `/v1/admin/cases/{caseId}/decision`    | admin | Body `{"decision": "confirm"\|"reject", "note"?}` → `204`; `409` if already decided. Confirm adds the account to the list (reason = note, else the first report's reason) and bumps the version in the same transaction. |

`Case` = `{"id", "platform", "handle", "status", "reporters", "agree", "disagree", "priority", "createdAt", "updatedAt", "proofs": [{"url", "reason", "createdAt"}], "myVote"}`. `myVote` is derived from `X-Client-Id` when sent.

**Priority** is the Wilson score lower bound (z = 1.96) of p = (reporters + agree) / n with n = reporters + agree + disagree, recomputed on every report and vote and indexed for sorting. Ties go to larger n, then the older case. Cursors are opaque.

Admin requests need `Authorization: Bearer <ADMIN_TOKEN>`, otherwise `401`.

CORS (`Access-Control-Allow-Origin: *`, headers `Content-Type, If-None-Match, X-Client-Id`) is enabled on `/v1/buzzers` and all `/v1/reports` routes so the Safari extension can call them. Admin routes send no CORS headers.

### Schema migrations

`PRAGMA user_version` tracks the schema; migrations run on start in one transaction. Upgrading from the first version drops the old `reports` table: those reports had no proofs or reporter identity to carry over.

## Limits

- Per IP, in memory, per process (reset on restart): 10 reports/min and 60 votes/min, then `429`.
- JSON bodies are capped at 8 KB; buzzer `reason` and decision `note` at 500 characters.
- Proofs are links only; the server stores no uploads.
- Single process, single SQLite file. Behind a reverse proxy the rate limit sees the proxy's IP; run it directly or limit at the proxy.
- No moderation UI: use `GET /v1/admin/queue` and the decision endpoint.
