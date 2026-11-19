// Shared page runtime. Expects SaneFeedCore and a site adapter (SaneFeedAdapter) to be loaded first.
//
// Adapter contract:
//   platform: "x" | "instagram"
//   reelsRedirect(location): path to replace a short-video route with, or null
//   findPosts(): post elements currently in the DOM
//   describe(post): { key, handle, isReel } or null while the post is still rendering
(() => {
  "use strict";
  const ext = globalThis.browser ?? globalThis.chrome;
  const core = globalThis.SaneFeedCore;
  const adapter = globalThis.SaneFeedAdapter;
  const { platform } = adapter;
  const html = document.documentElement;

  let settings = core.mergeSettings();
  let serverEntries = [];
  let blockSet = new Set();
  let counts = null;
  const reported = new Set(); // post keys already sent to the background from this page
  const bars = new WeakMap(); // hidden post -> its "Hidden · @handle" bar

  // ---- Posts ---------------------------------------------------------------

  function verdictFor(info) {
    if (settings.blockReels && info.isReel) return "reel";
    if (info.handle && core.isBuzzer(platform, info.handle, blockSet, settings.heuristic)) return "buzzer";
    return "visible";
  }

  function reset(post) {
    viewObserver.unobserve(post);
    post.classList.remove("sanefeed-hidden");
    bars.get(post)?.remove();
    bars.delete(post);
    delete post.dataset.sanefeed;
    delete post.dataset.sanefeedKey;
  }

  function collapse(post, handle) {
    const bar = document.createElement("div");
    bar.className = "sanefeed-bar";
    const label = document.createElement("span");
    label.textContent = core.hiddenLabel(handle);
    const show = document.createElement("button");
    show.type = "button";
    show.textContent = "Show";
    show.addEventListener("click", () => {
      // Revealed posts stay uncounted: the user chose to look past the filter.
      post.classList.remove("sanefeed-hidden");
      bar.remove();
      bars.delete(post);
    });
    bar.append(label, show);
    post.before(bar);
    bars.set(post, bar);
  }

  function apply(post) {
    const info = adapter.describe(post);
    if (!info) return;
    const verdict = verdictFor(info);
    // Sites recycle elements while scrolling, so the key is checked as well as the verdict.
    if (post.dataset.sanefeed === verdict && post.dataset.sanefeedKey === info.key) return;
    if (post.dataset.sanefeed) reset(post);
    post.dataset.sanefeed = verdict;
    post.dataset.sanefeedKey = info.key;
    if (verdict === "visible") {
      viewObserver.observe(post);
      return;
    }
    post.classList.add("sanefeed-hidden");
    if (verdict === "buzzer") collapse(post, info.handle);
  }

  function rescanAll() {
    for (const post of document.querySelectorAll("[data-sanefeed]")) reset(post);
    scan();
  }

  // ---- Counting ------------------------------------------------------------

  function onView(entries) {
    for (const entry of entries) {
      const post = entry.target;
      const rootHeight = entry.rootBounds ? entry.rootBounds.height : window.innerHeight;
      // A post taller than two screens never reaches ratio 0.5; half a screen of it counts too.
      const seen = entry.intersectionRatio >= 0.5 || entry.intersectionRect.height >= rootHeight * 0.5;
      if (!entry.isIntersecting || !seen || post.dataset.sanefeed !== "visible") continue;
      viewObserver.unobserve(post);
      const key = post.dataset.sanefeedKey;
      if (reported.has(key)) continue;
      reported.add(key);
      ext.runtime.sendMessage({ type: "seen", platform, key }).catch(() => reported.delete(key));
    }
  }

  const viewObserver = new IntersectionObserver(onView, { threshold: [0.25, 0.5] });

  // ---- Daily limit overlay -------------------------------------------------

  let overlay = null;
  let midnightTimer = 0;

  function updateOverlay() {
    const now = new Date();
    const count = core.countFor(counts, platform, now);
    const limit = settings.dailyLimits[platform];
    if (count < limit) {
      overlay?.remove();
      overlay = null;
      html.classList.remove("sanefeed-locked");
    } else {
      if (!overlay) {
        overlay = document.createElement("div");
        overlay.className = "sanefeed-overlay";
        overlay.setAttribute("role", "dialog");
        overlay.setAttribute("aria-modal", "true");
        overlay.setAttribute("aria-labelledby", "sanefeed-overlay-message");
        const panel = document.createElement("div");
        panel.className = "sanefeed-overlay__panel";
        const title = document.createElement("p");
        title.className = "sanefeed-overlay__title";
        title.textContent = "Daily limit reached";
        const message = document.createElement("p");
        message.id = "sanefeed-overlay-message";
        message.className = "sanefeed-overlay__message";
        panel.append(title, message);
        overlay.append(panel);
      }
      overlay.querySelector(".sanefeed-overlay__message").textContent = core.limitMessage(count, limit, platform);
      if (!overlay.isConnected) html.append(overlay);
      html.classList.add("sanefeed-locked");
    }
    clearTimeout(midnightTimer);
    midnightTimer = setTimeout(updateOverlay, core.msUntilMidnight(now) + 1000);
  }

  // ---- Scanning ------------------------------------------------------------

  function scan() {
    if (settings.blockReels) {
      const target = adapter.reelsRedirect(location);
      if (target) {
        location.replace(target);
        return;
      }
    }
    for (const post of adapter.findPosts()) apply(post);
  }

  let frame = 0;
  function scheduleScan() {
    if (!frame) frame = requestAnimationFrame(() => {
      frame = 0;
      scan();
    });
  }

  // ---- State ---------------------------------------------------------------

  function applySettings(stored) {
    settings = core.mergeSettings(stored);
    html.dataset.sanefeedReels = settings.blockReels ? "on" : "off";
    blockSet = core.buildBlockSet(serverEntries, settings.manualList);
  }

  ext.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;
    if (changes.counts) counts = changes.counts.newValue;
    if (changes.buzzerList) serverEntries = changes.buzzerList.newValue?.entries || [];
    if (changes.settings || changes.buzzerList) {
      applySettings(changes.settings ? changes.settings.newValue : settings);
      rescanAll();
    }
    if (changes.counts || changes.settings) updateOverlay();
  });

  ext.storage.local.get(["settings", "buzzerList", "counts"]).then((stored) => {
    counts = stored.counts;
    serverEntries = stored.buzzerList?.entries || [];
    applySettings(stored.settings);
    scan();
    updateOverlay();
    new MutationObserver(scheduleScan).observe(html, { childList: true, subtree: true });
    window.addEventListener("popstate", scheduleScan);
  });
})();
