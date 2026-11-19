// Pure logic shared by content scripts, background, popup and tests.
// No DOM or extension APIs here. Loaded as a classic script (exposes globalThis.SaneFeedCore)
// and as CommonJS under `bun test`.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.SaneFeedCore = api;
})(globalThis, function () {
  "use strict";

  const PLATFORMS = Object.freeze(["x", "instagram"]);
  const PLATFORM_LABELS = Object.freeze({ x: "X", instagram: "Instagram" });
  const HANDLE_PATTERNS = Object.freeze({
    x: /^[a-z0-9_]{1,15}$/,
    instagram: /^[a-z0-9._]{1,30}$/,
  });
  const HEURISTIC = /\d{6,}$/;
  const DEFAULT_SERVER_URL = "https://api.sanefeed.app";
  const DEFAULT_LIMIT = 50;
  const MIN_LIMIT = 1;
  const MAX_LIMIT = 1000;

  function normalizeHandle(raw) {
    return String(raw ?? "").trim().replace(/^@/, "").toLowerCase();
  }

  function isValidHandle(platform, handle) {
    const pattern = HANDLE_PATTERNS[platform];
    return pattern !== undefined && pattern.test(handle);
  }

  /** Normalized handle, or null when it is not a valid handle for the platform. */
  function parseHandle(platform, raw) {
    const handle = normalizeHandle(raw);
    return isValidHandle(platform, handle) ? handle : null;
  }

  function heuristicFlags(handle) {
    return HEURISTIC.test(handle);
  }

  function entryKey(platform, handle) {
    return platform + ":" + handle;
  }

  /** Set of "platform:handle" keys from the server list and the manual list. */
  function buildBlockSet(serverEntries, manualList) {
    const set = new Set();
    for (const entry of [...(serverEntries || []), ...(manualList || [])]) {
      set.add(entryKey(entry.platform, entry.handle));
    }
    return set;
  }

  function isBuzzer(platform, handle, blockSet, heuristicOn) {
    return blockSet.has(entryKey(platform, handle)) || (heuristicOn && heuristicFlags(handle));
  }

  /** Local calendar day as YYYY-MM-DD; the counter resets when this changes. */
  function dayKey(date) {
    const pad = (n) => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  }

  function msUntilMidnight(now) {
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
    return midnight.getTime() - now.getTime();
  }

  function emptyCounts(day) {
    return { day, seen: { x: [], instagram: [] } };
  }

  /** Stored counts if they belong to today, otherwise a fresh day. */
  function currentCounts(counts, now) {
    const day = dayKey(now);
    return counts && counts.day === day ? counts : emptyCounts(day);
  }

  function countFor(counts, platform, now) {
    return (currentCounts(counts, now).seen[platform] || []).length;
  }

  /**
   * Records a unique post for today. Posts past the limit are not stored, so the
   * per-day list stays bounded by the limit.
   */
  function recordSeen(counts, platform, postKey, now, limit) {
    const today = currentCounts(counts, now);
    const seen = today.seen[platform] || [];
    if (seen.includes(postKey) || seen.length >= limit) {
      return { counts: today, count: seen.length, added: false };
    }
    const next = { day: today.day, seen: { ...today.seen, [platform]: [...seen, postKey] } };
    return { counts: next, count: seen.length + 1, added: true };
  }

  function clampLimit(value) {
    const n = Math.round(Number(value));
    if (!Number.isFinite(n)) return null;
    return Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, n));
  }

  /** Trimmed http(s) origin + path without trailing slash, or null. */
  function normalizeServerUrl(raw) {
    let url;
    try {
      url = new URL(String(raw ?? "").trim());
    } catch {
      return null;
    }
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    return (url.origin + url.pathname).replace(/\/+$/, "");
  }

  /** Valid, normalized, de-duplicated {platform, handle} entries. */
  function sanitizeEntries(list) {
    const out = [];
    const keys = new Set();
    for (const item of Array.isArray(list) ? list : []) {
      if (!item || !PLATFORMS.includes(item.platform)) continue;
      const handle = parseHandle(item.platform, item.handle);
      const key = handle && entryKey(item.platform, handle);
      if (!key || keys.has(key)) continue;
      keys.add(key);
      out.push({ platform: item.platform, handle });
    }
    return out;
  }

  /** Settings with defaults filled in and invalid values dropped. */
  function mergeSettings(stored) {
    const s = stored || {};
    const limits = s.dailyLimits || {};
    return {
      dailyLimits: {
        x: clampLimit(limits.x) ?? DEFAULT_LIMIT,
        instagram: clampLimit(limits.instagram) ?? DEFAULT_LIMIT,
      },
      blockReels: typeof s.blockReels === "boolean" ? s.blockReels : true,
      heuristic: typeof s.heuristic === "boolean" ? s.heuristic : true,
      serverUrl: normalizeServerUrl(s.serverUrl) ?? DEFAULT_SERVER_URL,
      manualList: sanitizeEntries(s.manualList),
    };
  }

  /** Validates a GET /v1/buzzers body; throws on a malformed response. */
  function parseBuzzerList(body) {
    if (!body || !Number.isInteger(body.version) || !Array.isArray(body.entries)) {
      throw new Error("Malformed buzzer list");
    }
    return {
      version: body.version,
      updatedAt: String(body.updatedAt ?? ""),
      entries: sanitizeEntries(body.entries),
    };
  }

  // ---- Reports (v2) ---------------------------------------------------------

  const REASON_MIN = 10;
  const REASON_MAX = 1000;
  const PROOFS_MAX = 5;
  const PROOF_URL_MAX = 500;
  const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  const HANDLE_RULES = {
    x: "letters, numbers and _, up to 15 characters",
    instagram: "letters, numbers, . and _, up to 30 characters",
  };

  function isClientId(value) {
    return typeof value === "string" && UUID_V4.test(value);
  }

  function proofUrlError(raw) {
    if (raw.length > PROOF_URL_MAX) return `Link must be ${PROOF_URL_MAX} characters or fewer.`;
    try {
      if (new URL(raw).protocol === "https:") return null;
    } catch {
      // fall through
    }
    return "Use a full link starting with https://";
  }

  function isProofUrl(url) {
    return typeof url === "string" && proofUrlError(url) === null;
  }

  /**
   * Validates the report form with the server's rules. Blank proof rows are ignored.
   * Returns { body, errors }; body is null when any error is set. proofItems lines up with input.proofs.
   */
  function validateReport(input) {
    const platform = input.platform;
    const errors = { platform: null, handle: null, reason: null, proofs: null, proofItems: [] };
    if (!PLATFORMS.includes(platform)) errors.platform = "Choose X or Instagram.";
    const handle = parseHandle(platform, input.handle);
    if (!handle && !errors.platform) {
      errors.handle = `Enter a valid ${PLATFORM_LABELS[platform]} handle (${HANDLE_RULES[platform]}).`;
    }
    const reason = String(input.reason ?? "").trim();
    if (reason.length < REASON_MIN) {
      errors.reason = `Give a reason of at least ${REASON_MIN} characters (${reason.length} so far).`;
    } else if (reason.length > REASON_MAX) {
      errors.reason = `Keep the reason to ${REASON_MAX} characters (${reason.length} now).`;
    }
    const proofs = [];
    let filled = 0;
    for (const raw of input.proofs || []) {
      const url = String(raw ?? "").trim();
      const error = url ? proofUrlError(url) : null;
      errors.proofItems.push(error);
      if (url) filled += 1;
      if (url && !error) proofs.push(url);
    }
    if (filled === 0) errors.proofs = "Add at least one proof link.";
    else if (filled > PROOFS_MAX) errors.proofs = `Add at most ${PROOFS_MAX} proof links.`;
    const ok = !errors.platform && !errors.handle && !errors.reason && !errors.proofs && proofs.length === filled;
    return { body: ok ? { platform, handle, reason, proofs } : null, errors };
  }

  function reportsListPath(platform, cursor, limit = 50) {
    const params = new URLSearchParams({ platform, limit: String(limit) });
    if (cursor) params.set("cursor", cursor);
    return `/v1/reports?${params}`;
  }

  function reportResultMessage(status) {
    return status === 201
      ? "Report sent. A new case was opened for review."
      : "Report sent. It was added to the open case for this account.";
  }

  /** User-facing text for a failed report or vote request. */
  function requestErrorMessage(status, serverError) {
    if (status === 403) return "You reported this account";
    if (status === 429) return "Too many requests. Try again in a minute.";
    return serverError || `Server returned ${status}.`;
  }

  /** Case with the counts and vote from a POST /votes response applied. */
  function applyVote(reportCase, result) {
    return { ...reportCase, agree: result.agree, disagree: result.disagree, myVote: result.myVote };
  }

  function limitMessage(count, limit, platform) {
    return `You've seen ${count} of ${limit} posts on ${PLATFORM_LABELS[platform]} today. Resets at midnight.`;
  }

  function progressLabel(count, limit) {
    return `${count} of ${limit} posts today`;
  }

  function hiddenLabel(handle) {
    return `Hidden · @${handle} is on your buzzer list`;
  }

  return Object.freeze({
    PLATFORMS,
    PLATFORM_LABELS,
    DEFAULT_SERVER_URL,
    REASON_MAX,
    PROOFS_MAX,
    MIN_LIMIT,
    MAX_LIMIT,
    normalizeHandle,
    isValidHandle,
    parseHandle,
    heuristicFlags,
    entryKey,
    buildBlockSet,
    isBuzzer,
    dayKey,
    msUntilMidnight,
    currentCounts,
    countFor,
    recordSeen,
    clampLimit,
    normalizeServerUrl,
    mergeSettings,
    parseBuzzerList,
    isClientId,
    isProofUrl,
    validateReport,
    reportsListPath,
    reportResultMessage,
    requestErrorMessage,
    applyVote,
    limitMessage,
    progressLabel,
    hiddenLabel,
  });
});
