# SaneFeed

A wellbeing layer over X (Twitter) and Instagram:

- **No reels.** Reels and short-video screens are blocked; their tabs are covered.
- **Daily post limit.** Each unique post counts once per day, per platform (default 50). At the limit the app is covered until local midnight.
- **Buzzer filter.** Posts by accounts on the shared buzzer list, your manual list, or with auto-generated handles (ending in 6+ digits) are hidden and don't count.
- **Community review.** Anyone can report an account with proof links; others agree or disagree; cases are queued for manual review by a Wilson-score priority. Confirmed cases join the list.

| Directory | What | Docs |
| --- | --- | --- |
| `android/` | Native Kotlin app (AccessibilityService + Compose settings). Primary target. | [android/README.md](android/README.md) |
| `ios/` | Safari Web Extension for x.com / instagram.com. | [ios/README.md](ios/README.md) |
| `server/` | Bun + SQLite API: buzzer list, reports, votes, review queue. | [server/README.md](server/README.md) |

## Why iOS is a browser extension

iOS sandboxing forbids any app from reading or drawing over another app's UI, so the native X and Instagram apps cannot be filtered. On iOS SaneFeed runs in Safari on the web versions; pair it with Screen Time to limit the native apps.

## Quick start

```sh
cd server && ADMIN_TOKEN=change-me bun run start     # http://localhost:8787
cd android && ./gradlew :app:installDebug             # talks to https://api.sanefeed.byhan.id
cd ios && bun test                                    # extension logic tests
```

## Known limits

X and Instagram ship UI changes often. Detection selectors live in one file per platform (`android/.../XSelectors.kt`, `InstagramSelectors.kt`, `ios/SaneFeedExtension/content/sites/`) and need upkeep. Text fallbacks assume the apps are in English.
