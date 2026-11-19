// X (Twitter) adapter. Selectors target x.com's stable data-testid attributes.
(() => {
  "use strict";
  const core = globalThis.SaneFeedCore;
  const PERMALINK = /^\/([A-Za-z0-9_]{1,15})\/status\/(\d+)/;

  globalThis.SaneFeedAdapter = {
    platform: "x",

    reelsRedirect(location) {
      const path = location.pathname;
      if (/^\/i\/(videos?|immersive)(\/|$)/.test(path)) return "/home";
      // The full-screen player (/status/<id>/video/<n>) swipes into an endless video feed.
      const player = path.match(/^(\/[A-Za-z0-9_]{1,15}\/status\/\d+)\/video\/\d+/);
      return player ? player[1] : null;
    },

    findPosts() {
      return document.querySelectorAll('article[data-testid="tweet"]');
    },

    describe(article) {
      // The first timestamp link is the post's own permalink; quoted posts render theirs without a link.
      const link = article.querySelector('a[href*="/status/"] time')?.closest("a");
      const match = link?.getAttribute("href").match(PERMALINK);
      if (!match) return null;
      return { key: match[2], handle: core.normalizeHandle(match[1]), isReel: false };
    },
  };
})();
