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

## Install (Android)

1. On the phone, open [Releases](https://github.com/andi-frame/SaneFeed/releases/latest) and download `SaneFeed-<version>.apk`.
2. Open the file. Android asks you to allow installs from that source (your browser or file manager): allow it, go back and tap **Install**.
3. Open SaneFeed and follow **Setup**. On Android 13 and newer, turn on the service only after Settings → Apps → SaneFeed → ⋮ → **Allow restricted settings**; Android blocks accessibility services from sideloaded apps until you do.
4. Turn on **SaneFeed** in Settings → Accessibility (sometimes under "Downloaded apps").
5. Turn off battery optimization for SaneFeed. On Xiaomi, Redmi and POCO (HyperOS/MIUI), also turn on **Autostart** in the app's settings, or the system stops the service.

Updates install over the old version as long as they are signed with the same key. To check a download, compare it against `SHA256SUMS.txt` from the same release; the APK signing certificate's SHA-256 fingerprint is:

```
98:C7:ED:AA:CA:40:08:7E:5F:1C:CB:4E:62:68:60:27:2B:86:03:62:E2:4C:E3:E6:34:5F:B3:ED:78:55:15:D8
```

Check it with `apksigner verify --print-certs SaneFeed-<version>.apk`. A debug build installs separately as `app.sanefeed.debug` and can sit next to a release install.

## Why iOS is a browser extension

iOS sandboxing forbids any app from reading or drawing over another app's UI, so the native X and Instagram apps cannot be filtered. On iOS SaneFeed runs in Safari on the web versions; pair it with Screen Time to limit the native apps.

## Quick start

```sh
cd server && ADMIN_TOKEN=change-me bun run start     # http://localhost:8787
cd android && ./gradlew :app:installDebug             # talks to https://api.sanefeed.byhan.id
cd ios && bun test                                    # extension logic tests
```

## Release

CI (`.github/workflows/ci.yml`) runs the server, iOS and Android tests, Android lint and an unsigned release build on every push and pull request to `main`. Pushing a `v<major>.<minor>.<patch>` tag runs the same checks, then builds a signed APK and AAB and publishes them with `SHA256SUMS.txt` as a GitHub Release (`.github/workflows/release.yml`):

```sh
git tag v1.0.0 && git push origin v1.0.0
```

The tag sets `versionName` (`1.0.0`) and `versionCode` (`major*10000 + minor*100 + patch`, so minor and patch stay below 100). A tag with a suffix such as `v1.1.0-rc.1` is published as a pre-release.

Signing uses the keystore in `~/.sanefeed/` (`release.jks`, passwords in `release.properties`), stored in the repository secrets `SANEFEED_KEYSTORE_BASE64`, `SANEFEED_KEYSTORE_PASSWORD`, `SANEFEED_KEY_ALIAS` and `SANEFEED_KEY_PASSWORD`. **Back up `~/.sanefeed/` somewhere safe.** If the key is lost, no future release can update existing installs; everyone would have to uninstall and lose their settings. To build a signed release locally, export the variables in `release.properties` (or pass them as `-P` properties) and run `./gradlew :app:assembleRelease`. Without them the release build is unsigned.

Google Play is not a target. Publishing there would need the Play Console accessibility API declaration and a prominent-disclosure review for the AccessibilityService.

## Known limits

X and Instagram ship UI changes often. Detection selectors live in one file per platform (`android/.../XSelectors.kt`, `InstagramSelectors.kt`, `ios/SaneFeedExtension/content/sites/`) and need upkeep. Text fallbacks assume the apps are in English.
