# SaneFeed for Android

An accessibility service that runs on top of the X (`com.twitter.android`) and Instagram (`com.instagram.android`) apps:

- **Reels and video blocking.** On a Reels screen (Instagram) or the immersive video viewer (X), it presses Back (at most once every 1.5 s, three times in a row, so it can't loop) and covers the screen. The Reels tab and X's Video tab are covered by a blocker that swallows taps.
- **Daily limit.** Counts unique posts per platform per local day. A post counts once, when at least half of it is on screen (a post cut off by the top or bottom of the feed counts once it fills half the feed). Keys are deduplicated in a bounded set that is persisted for the day. At the limit a full-screen panel appears with a "Leave app" button. Counts reset at local midnight. Default limit: 50 per platform.
- **Buzzer accounts.** A post is covered with "Hidden · @handle is on your buzzer list" when its author is on the server list, on your own list, or (toggle, on by default) the handle ends in 6 or more digits. Hidden posts don't count toward the limit.
- **Reports.** "Report an account" sends a reason and 1–5 https proof links to the server. "Review reports" lists open cases by priority with Agree/Disagree votes. Each install sends a random UUID as `X-Client-Id`, generated on first use. There are no accounts.

## Build

Requirements: JDK 17 or newer, Android SDK with platform 36. `local.properties` points at the SDK (`sdk.dir=...`).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug   # Windows: gradlew.bat
./gradlew :app:installDebug
```

Release builds use R8 and resource shrinking. They default to `https://api.sanefeed.byhan.id`; override with `-PsanefeedServerUrl=https://your.server` or set the URL in the app. Debug builds default to `http://127.0.0.1:8787`; run `adb reverse tcp:8787 tcp:8787` so the phone or emulator reaches the server on your computer. Plain HTTP is allowed in debug builds only.

## Turn on the service

1. Open SaneFeed and tap **Turn on**. Read the disclosure and tap **Agree and open Accessibility settings**.
2. In Android Settings → Accessibility, open **SaneFeed** (sometimes under "Downloaded apps" or "Installed apps") and turn it on.
3. On Android 13 and newer, sideloaded apps can't turn on accessibility services until you allow it: Settings → Apps → SaneFeed → ⋮ → **Allow restricted settings**, then repeat step 2.

## How it works

- `accessibility/SaneFeedAccessibilityService.kt` merges events (window state, content changes, scrolls) on the main thread so it scans at most once per 150 ms. It reads the node tree on a background thread and applies overlays back on the main thread.
- `accessibility/x/XAdapter.kt` and `accessibility/instagram/InstagramAdapter.kt` implement `PlatformAdapter`. Each one finds Reels/video screens, the posts on screen (author, stable key, bounds), and the tabs to cover.
- `accessibility/OverlayManager.kt` draws `TYPE_ACCESSIBILITY_OVERLAY` windows and reuses them from a pool. Overlays are removed when their post leaves the screen or when X and Instagram are no longer on screen.
- `data/BuzzerSyncWorker.kt` syncs the list every 6 hours on any network, and again each time the app is opened. It sends `If-None-Match`, so an unchanged list costs one 304. The list is cached in `filesDir/buzzers.json`.
- `core/` holds plain Kotlin logic (handles, heuristic, counter, JSON mapping, validation). JVM unit tests in `app/src/test` cover it.

## Limits

- **Selectors are fragile.** X and Instagram don't publish their view ids and rename them between releases. Every selector is in `accessibility/x/XSelectors.kt` and `accessibility/instagram/InstagramSelectors.kt`. Each list tries known view ids first, then content descriptions and text in English. If an app update breaks a selector, SaneFeed fails safe: nothing is hidden, blocked or counted for the parts that no longer match, and the app works as usual. Update the selector lists when that happens. Label fallbacks ("Reels", "Video") only work with English UI.
- Overlays follow the feed about 150 ms behind while you scroll.
- Post masks let touches through so the feed can still scroll. Tapping a masked post can still open it.
- A post that has no content signature (no image description or text) is keyed by its author alone, so other such posts from the same author that day don't count again.
- Picture-in-picture and split-screen are not handled specially; only the topmost X or Instagram window is read.
- iOS can't read other apps' screens, so iOS ships as a Safari extension instead (see `../ios`).
