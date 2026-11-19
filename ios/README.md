# SaneFeed for iOS (Safari Web Extension)

SaneFeed on iOS is a Safari Web Extension for `x.com` and `www.instagram.com`. It:

- removes reels and video feeds (Instagram `/reels/`, `/reel/…`, profile Reels tabs, the Reels nav item and video posts in the feed; X `/i/video…` and `/i/immersive…` routes, the full-screen `/status/<id>/video/<n>` player and their nav links);
- counts unique posts you have seen today per platform (a post counts once at least half of it, or half a screen of it, is on screen) and covers the page when you reach the limit: "You've seen 50 of 50 posts on Instagram today. Resets at midnight.";
- collapses posts from buzzer accounts into a bar ("Hidden · @handle is on your buzzer list"). An account is a buzzer if it is on the shared server list, on your own list, or (optional, on by default) its handle ends in 6 or more digits. Hidden posts are not counted;
- lets you report an account with a reason and 1 to 5 proof links, and vote Agree / Disagree on pending reports from other people.

## Why a Safari extension and not an app

iOS apps run in a sandbox: one app cannot read, overlay or modify another app's screen, and there is no equivalent of Android's AccessibilityService. The native X and Instagram apps therefore cannot be filtered on iOS. The most iOS offers for them is a total time limit through Screen Time. SaneFeed works on iOS only when you use X and Instagram in Safari.

## Layout

```
SaneFeedExtension/
  manifest.json          Manifest V3
  background.js          service worker: buzzer list sync (ETag, every 6 h), post counter, report/vote requests
  content/core.js        pure logic shared by every script and the tests
  content/main.js        page runtime: scanning, counting, overlay, buzzer bars
  content/sites/*.js     per-site adapters (post selectors, permalinks, reel routes)
  content/*.css          injected before first paint (hides reel nav items)
  popup/                 Settings, Report and Review tabs
  icons/
tests/core.test.js       bun tests for content/core.js
```

Plain ES2020, no bundler, no dependencies. Scripts use `browser.*` and fall back to `chrome.*`.

## Build and run

Tests (from `ios/`):

```sh
bun test
```

Safari on iOS (needs a Mac with Xcode 14+):

```sh
xcrun safari-web-extension-converter ios/SaneFeedExtension \
  --app-name SaneFeed --bundle-identifier app.sanefeed --swift --ios-only
```

Open the generated Xcode project, set your signing team for both the app and the extension target, and run it on a device or simulator. Then enable the extension in Settings > Apps > Safari > Extensions > SaneFeed and allow it on x.com and instagram.com (or tap the puzzle icon in Safari's address bar).

Desktop check without a Mac: load `ios/SaneFeedExtension` as an unpacked extension in Chrome (`chrome://extensions`, Developer mode).

## Server

The default server is `https://api.sanefeed.app` (granted in `host_permissions`). Another server URL can be set in Settings > Shared buzzer list; the extension then asks for access to that origin (`optional_host_permissions`). If access is declined, requests still work when the server sends CORS headers.

Endpoints used: `GET /v1/buzzers` (with `If-None-Match`), `POST /v1/reports`, `GET /v1/reports?platform=…`, `POST /v1/reports/{id}/votes`. Report and vote requests carry `X-Client-Id`, a random UUID generated once per install and kept in `storage.local`.

## Stored data (`storage.local`)

- `settings`: daily limits, toggles, server URL, your buzzer list
- `buzzerList`: last downloaded server list, its ETag and sync status
- `counts`: today's date and the post ids seen per platform (capped at the limit)
- `clientId`, `myReports`: install id and the case ids you reported (voting on those is disabled)

Nothing leaves the device except the requests above.

## Limits

- Works only in Safari (and Chromium browsers), not in the X or Instagram apps.
- Site markup changes without notice. Selectors rely on X's `data-testid` attributes and Instagram's `<article>` and permalink structure, and may need updates.
- Instagram publishes all new videos as reels, so every feed post with a video is removed while reel blocking is on.
- Blocking the X full-screen video player also blocks opening a single video full screen; inline playback still works.
- Counts live in the browser's extension storage and are separate from the Android app's counts.
- Requires iOS / Safari 15.4 or later (Manifest V3 service worker).
