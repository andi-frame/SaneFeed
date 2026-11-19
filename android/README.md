# SaneFeed for Android

An accessibility service that runs on top of the X (`com.twitter.android`) and Instagram (`com.instagram.android`) apps:

- **Reels and video blocking.** On a Reels screen (Instagram) or the immersive video viewer (X), it presses Back (at most once every 1.5 s, three times in a row, so it can't loop) and covers the screen. The Reels tab and X's Video tab are covered by a blocker that swallows taps.
- **Daily limit.** Counts unique posts per platform per local day. A post counts once, when at least half of it is on screen (a post cut off by the top or bottom of the feed counts once it fills half the feed). Keys are deduplicated in a bounded set that is persisted for the day. At the limit a full-screen panel appears with a "Leave app" button. Counts reset at local midnight. Default limit: 50 per platform.
- **Buzzer accounts.** Content is covered with "Hidden · @handle is on your buzzer list" when its author is on the server list, on your own list, or (toggle, on by default) the handle ends in 6 or more digits: feed posts, replies and comments, quoted posts and notification replies (X), account rows in search and typeahead, stories tray avatars and Explore tiles (Instagram). On X, a post detail opened by tapping a masked post stays covered. Opening such an account's profile or story shows a full-screen panel with a "Go back" button. Hidden items don't count toward the limit; only feed posts count.
- **Reports.** "Report an account" sends a reason and 1–5 https proof links to the server. "Review reports" lists open cases by priority with Agree/Disagree votes. Each install sends a random UUID as `X-Client-Id`, generated on first use. There are no accounts.

## Build

Requirements: JDK 17 or newer, Android SDK with platform 36. `local.properties` points at the SDK (`sdk.dir=...`).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug   # Windows: gradlew.bat
./gradlew :app:installDebug
```

Release builds use R8 and resource shrinking. All builds default to `https://api.sanefeed.byhan.id`; the URL can also be changed in the app. For a local server, build with `-PsanefeedServerUrl=http://127.0.0.1:8787` and run `adb reverse tcp:8787 tcp:8787` so the phone or emulator reaches your computer. Plain HTTP is allowed in debug builds only.

## Release

Debug builds install as `app.sanefeed.debug` ("SaneFeed Debug"), so they sit next to a release install; build with `-PsanefeedDebugSuffix=false` to update a debug install made as plain `app.sanefeed`. Release builds are signed when `SANEFEED_KEYSTORE_PATH`, `SANEFEED_KEYSTORE_PASSWORD`, `SANEFEED_KEY_ALIAS` and `SANEFEED_KEY_PASSWORD` are set as environment variables or Gradle properties, and unsigned otherwise. `-PversionName` and `-PversionCode` set the version (default `1.0.0` / `1`). Tagged releases are built by CI; see [Release](../README.md#release) in the root README.

## Turn on the service

1. Open SaneFeed. The **Setup** section at the top lists what's still needed. Tap **Open settings** next to Accessibility service, read the disclosure and tap **Agree and open Accessibility settings**.
2. In Android Settings → Accessibility, open **SaneFeed** (sometimes under "Downloaded apps" or "Installed apps") and turn it on.
3. On Android 13 and newer, sideloaded apps can't turn on accessibility services until you allow it: Settings → Apps → SaneFeed → ⋮ → **Allow restricted settings**, then repeat step 2. Setup shows this hint, with an **App info** button, when SaneFeed wasn't installed from Play.
4. Recommended: turn off battery optimization for SaneFeed. On Xiaomi, Redmi and POCO phones, also turn on **Autostart**. Android has no API to read the Autostart switch, so Setup always lists it as a manual step on those phones.

## Setup and health check

Setup marks each item **Done**, **Needed**, **Recommended** or **Check**. Below the checklist, each app shows its installed version and whether SaneFeed is working there. It updates live while the screen is open:

| Status | Meaning |
|---|---|
| Off | The accessibility service is turned off. |
| Not running | It is on in Settings, but Android hasn't started it. Turn it off and on again. |
| Not seen yet | No posts seen since the service started. Open the app and scroll. |
| No posts recognized | 20+ feed scans found no post with a readable author. The selectors are probably outdated for that app version. |
| Working | Time of the last scan and how many posts it recognized. |
| Last scan failed | The most recent scan threw an error (shown); the next successful scan clears it. |

The service records this in `health/ServiceHealth.kt`, in memory only. It is cleared when the service stops. **Copy diagnostic report** copies plain text: app, device and Android versions, X and Instagram versions, setup state, settings, and health counters per app. It never includes handles or post content.

## How it works

- `accessibility/SaneFeedAccessibilityService.kt` scans on the first event after a quiet spell, then back to back while events keep coming (at most one scan start per 60 ms), plus once after the last event. It reads the node tree on a background thread and applies overlays back on the main thread. Between scans, masks follow the scroll deltas the apps report (Instagram sends pixel deltas; X's Compose lists only a position, whose jumps are smoothed in `core/MaskMotion.kt`) and are widened by the last delta while the list moves.
- `accessibility/x/XAdapter.kt` and `accessibility/instagram/InstagramAdapter.kt` implement `PlatformAdapter`. Each one finds Reels/video screens, the authored items on screen (author, stable key, bounds, the visible part of their list) and the account of a full-screen profile or story. X is read with one bounded tree walk; app bars, tab rows and the bottom bar drawn over the list are excluded from the mask area. Instagram posts span from header to footer; the area above the first header goes to the post before it (`instagram/FeedLayout.kt`).
- `accessibility/OverlayManager.kt` draws opaque `TYPE_ACCESSIBILITY_OVERLAY` windows. A mask whose item stays on screen keeps its window and is only moved, and only when its bounds change. Overlays are removed when their item leaves the screen or when X and Instagram are no longer on screen.
- Debug builds only: `src/debug/.../TestHooks.kt` lets `adb shell am broadcast` swipe, tap, go back, type and log the node tree through the service, for phones that deny shell input (HyperOS). Release builds have neither the hook nor the gesture capability.
- `data/BuzzerSyncWorker.kt` syncs the list every 6 hours on any network, and again each time the app is opened. It sends `If-None-Match`, so an unchanged list costs one 304. The list is cached in `filesDir/buzzers.json`.
- `core/` holds plain Kotlin logic (handles, heuristic, counter, JSON mapping, validation). `health/` holds the setup checks, health record, verdicts and diagnostic report. JVM unit tests in `app/src/test` cover both.

## Limits

- **Selectors are fragile.** X and Instagram don't publish their view ids and rename them between releases. Every selector is in `accessibility/x/XSelectors.kt` and `accessibility/instagram/InstagramSelectors.kt`. Each list tries known view ids first, then content descriptions and text in English. X 12.x is built with Jetpack Compose. Its posts are found by walking the tree for the `timeline_post` test tag, because id and text searches can't see Compose nodes. If an app update breaks a selector, SaneFeed fails safe: nothing is hidden, blocked or counted for the parts that no longer match, and the app works as usual. Setup then shows "No posts recognized" for that app. Update the selector lists when that happens. Label fallbacks ("Reels", "Video") only work with English UI.
- Masks follow scrolling from the apps' scroll events, which arrive about every 100 ms; during a fast fling a mask can trail its post by up to one event and is widened to compensate, so it may briefly overlap the neighbouring post.
- Post masks let touches through so the feed can still scroll. Tapping a masked post can still open it; on X its detail screen stays covered, on Instagram the opened post is masked like any other.
- X's post detail exposes no author for the opened post itself, so a detail opened from a notification, link or unmasked place isn't covered (its replies are). X notification rows that show only a display name ("… liked your post") and reposts are attributed to the original author only.
- A post that has no content signature (no image description or text) is keyed by its author alone, so other such posts from the same author that day don't count again.
- Picture-in-picture and split-screen are not handled specially; only the topmost X or Instagram window is read.
- iOS can't read other apps' screens, so iOS ships as a Safari extension instead (see `../ios`).
