# SaneFeed buzzer server

Serves the shared list of buzzer accounts (paid or coordinated propaganda accounts) that SaneFeed clients hide, and collects community reports for manual review. Bun + built-in SQLite, no runtime dependencies.

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

| Method   | Path                               | Auth  | Result |
| -------- | ---------------------------------- | ----- | ------ |
| `GET`    | `/v1/buzzers`                      | none  | `200 {"version", "updatedAt", "entries": [{"platform", "handle", "reason"}]}` with `ETag: "<version>"`, `Cache-Control: public, max-age=300`. `304` when `If-None-Match` matches. |
| `POST`   | `/v1/reports`                      | none  | Body `{"platform", "handle", "reason"?}` → `202 {"status":"received"}`. Stored as pending; never added to the list automatically. |
| `PUT`    | `/v1/buzzers/{platform}/{handle}`  | admin | Body `{"reason"?}` (optional) → `204`. |
| `DELETE` | `/v1/buzzers/{platform}/{handle}`  | admin | `204`, or `404` if not listed. |
| `GET`    | `/v1/reports`                      | admin | `200 {"reports": [{"id", "platform", "handle", "reason", "createdAt"}]}` |

Admin requests need `Authorization: Bearer <ADMIN_TOKEN>`, otherwise `401`. Every change to the list increments `version` in the same transaction as the write; a `PUT` that changes nothing leaves it unchanged.

CORS (`Access-Control-Allow-Origin: *`) is enabled for `GET /v1/buzzers` and `POST /v1/reports` so the Safari extension can call them.

## Limits

- `POST /v1/reports`: 10 per IP per minute (in memory, per process; resets on restart). Over the limit returns `429`.
- JSON bodies are capped at 4 KB; `reason` at 500 characters.
- Single process, single SQLite file. Behind a reverse proxy the rate limit sees the proxy's IP; run it directly or limit at the proxy.
- No moderation UI: review `GET /v1/reports` and promote entries with `PUT`.
