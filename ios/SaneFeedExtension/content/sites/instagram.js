// Instagram adapter. Instagram's class names are obfuscated, so posts are found by
// <article> and permalinks (/p/<code>/, /reel/<code>/) instead.
(() => {
  "use strict";
  const core = globalThis.SaneFeedCore;
  const PERMALINK = /\/(p|reel)\/([A-Za-z0-9_-]+)/;
  const PROFILE_LINK = /^\/([A-Za-z0-9._]{1,30})\/?$/;
  const RESERVED = new Set(["explore", "reels", "reel", "p", "stories", "direct", "accounts", "about", "legal"]);

  function authorOf(article) {
    for (const link of article.querySelectorAll("a[href]")) {
      const match = link.getAttribute("href").match(PROFILE_LINK);
      if (match && !RESERVED.has(match[1].toLowerCase())) return core.normalizeHandle(match[1]);
    }
    return null;
  }

  globalThis.SaneFeedAdapter = {
    platform: "instagram",

    reelsRedirect(location) {
      const path = location.pathname;
      if (/^\/reels?(\/|$)/.test(path)) return "/";
      const profileReels = path.match(/^(\/[A-Za-z0-9._]{1,30}\/)reels\/?$/);
      return profileReels ? profileReels[1] : null;
    },

    findPosts() {
      return document.querySelectorAll("article");
    },

    describe(article) {
      const match = article.querySelector('a[href*="/p/"], a[href*="/reel/"]')?.getAttribute("href").match(PERMALINK);
      const handle = authorOf(article);
      if (!match || !handle) return null;
      // Instagram publishes every new video post as a reel, so any feed video is treated as one.
      const isReel = match[1] === "reel" || article.querySelector('a[href*="/reel/"], video') !== null;
      return { key: match[2], handle, isReel };
    },
  };
})();
