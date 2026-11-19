import { describe, expect, test } from "bun:test";
import core from "../SaneFeedExtension/content/core.js";

describe("handles", () => {
  test("normalizeHandle trims, strips one leading @ and lowercases", () => {
    expect(core.normalizeHandle("  @Budi_83 ")).toBe("budi_83");
    expect(core.normalizeHandle("@@x")).toBe("@x");
    expect(core.normalizeHandle(undefined)).toBe("");
  });

  test("isValidHandle applies per-platform rules", () => {
    expect(core.isValidHandle("x", "jack_dorsey")).toBe(true);
    expect(core.isValidHandle("x", "a".repeat(16))).toBe(false);
    expect(core.isValidHandle("x", "has.dot")).toBe(false);
    expect(core.isValidHandle("instagram", "has.dot_ok")).toBe(true);
    expect(core.isValidHandle("instagram", "a".repeat(31))).toBe(false);
    expect(core.isValidHandle("tiktok", "abc")).toBe(false);
  });

  test("parseHandle returns null for invalid input", () => {
    expect(core.parseHandle("x", "@Someone")).toBe("someone");
    expect(core.parseHandle("x", "bad handle")).toBeNull();
  });
});

describe("buzzer detection", () => {
  test("heuristic flags 6+ trailing digits only", () => {
    expect(core.heuristicFlags("budi83749201")).toBe(true);
    expect(core.heuristicFlags("123456")).toBe(true);
    expect(core.heuristicFlags("budi12345")).toBe(false);
    expect(core.heuristicFlags("1234567budi")).toBe(false);
  });

  test("isBuzzer combines server list, manual list and heuristic", () => {
    const set = core.buildBlockSet([{ platform: "x", handle: "server_one" }], [{ platform: "instagram", handle: "mine" }]);
    expect(core.isBuzzer("x", "server_one", set, false)).toBe(true);
    expect(core.isBuzzer("instagram", "server_one", set, false)).toBe(false);
    expect(core.isBuzzer("instagram", "mine", set, false)).toBe(true);
    expect(core.isBuzzer("x", "budi83749201", set, true)).toBe(true);
    expect(core.isBuzzer("x", "budi83749201", set, false)).toBe(false);
  });

  test("parseBuzzerList normalizes, drops invalid and duplicate entries", () => {
    const list = core.parseBuzzerList({
      version: 3,
      updatedAt: "2026-11-19T00:00:00Z",
      entries: [
        { platform: "x", handle: "@Alpha", reason: "spam" },
        { platform: "x", handle: "alpha", reason: null },
        { platform: "instagram", handle: "beta.gamma" },
        { platform: "x", handle: "not valid" },
        { platform: "mastodon", handle: "zeta" },
      ],
    });
    expect(list).toEqual({
      version: 3,
      updatedAt: "2026-11-19T00:00:00Z",
      entries: [
        { platform: "x", handle: "alpha" },
        { platform: "instagram", handle: "beta.gamma" },
      ],
    });
    expect(() => core.parseBuzzerList({ entries: [] })).toThrow();
  });
});

describe("daily counter", () => {
  const morning = new Date(2026, 10, 19, 9, 0);
  const night = new Date(2026, 10, 19, 23, 59);
  const tomorrow = new Date(2026, 10, 20, 0, 1);

  test("dayKey uses the local calendar day", () => {
    expect(core.dayKey(morning)).toBe("2026-11-19");
    expect(core.dayKey(tomorrow)).toBe("2026-11-20");
  });

  test("msUntilMidnight counts to the next local midnight", () => {
    expect(core.msUntilMidnight(night)).toBe(60 * 1000);
  });

  test("recordSeen counts unique posts per platform", () => {
    let counts = null;
    let r = core.recordSeen(counts, "x", "1", morning, 50);
    expect(r).toMatchObject({ count: 1, added: true });
    counts = r.counts;
    r = core.recordSeen(counts, "x", "1", night, 50);
    expect(r).toMatchObject({ count: 1, added: false });
    expect(r.counts).toBe(counts);
    r = core.recordSeen(counts, "instagram", "1", night, 50);
    expect(r.count).toBe(1);
    expect(core.countFor(r.counts, "x", night)).toBe(1);
  });

  test("recordSeen resets on a new day", () => {
    const { counts } = core.recordSeen(null, "x", "1", night, 50);
    expect(core.countFor(counts, "x", tomorrow)).toBe(0);
    const r = core.recordSeen(counts, "x", "1", tomorrow, 50);
    expect(r).toMatchObject({ count: 1, added: true });
    expect(r.counts.day).toBe("2026-11-20");
  });

  test("recordSeen stops storing at the limit", () => {
    let counts = null;
    for (const key of ["a", "b"]) counts = core.recordSeen(counts, "x", key, morning, 2).counts;
    const r = core.recordSeen(counts, "x", "c", morning, 2);
    expect(r).toMatchObject({ count: 2, added: false });
  });

  test("limit and progress wording", () => {
    expect(core.limitMessage(50, 50, "instagram")).toBe(
      "You've seen 50 of 50 posts on Instagram today. Resets at midnight."
    );
    expect(core.progressLabel(38, 50)).toBe("38 of 50 posts today");
    expect(core.hiddenLabel("budi")).toBe("Hidden · @budi is on your buzzer list");
  });
});

describe("settings", () => {
  test("defaults", () => {
    expect(core.mergeSettings()).toEqual({
      dailyLimits: { x: 50, instagram: 50 },
      blockReels: true,
      heuristic: true,
      serverUrl: "https://api.sanefeed.app",
      manualList: [],
    });
  });

  test("clamps limits and drops invalid values", () => {
    const s = core.mergeSettings({
      dailyLimits: { x: 0, instagram: "abc" },
      blockReels: false,
      heuristic: "yes",
      serverUrl: "ftp://example.com",
      manualList: [{ platform: "x", handle: "@Me" }, { platform: "x", handle: "me" }, { platform: "x", handle: "" }],
    });
    expect(s.dailyLimits).toEqual({ x: 1, instagram: 50 });
    expect(s.blockReels).toBe(false);
    expect(s.heuristic).toBe(true);
    expect(s.serverUrl).toBe("https://api.sanefeed.app");
    expect(s.manualList).toEqual([{ platform: "x", handle: "me" }]);
  });

  test("normalizeServerUrl strips trailing slashes", () => {
    expect(core.normalizeServerUrl(" https://example.com/api/ ")).toBe("https://example.com/api");
    expect(core.normalizeServerUrl("http://localhost:8787")).toBe("http://localhost:8787");
    expect(core.normalizeServerUrl("not a url")).toBeNull();
  });
});

describe("reports", () => {
  const valid = {
    platform: "x",
    handle: "@Budi83749201",
    reason: "  Posts identical campaign text  ",
    proofs: ["https://x.com/budi83749201/status/1", ""],
  };

  test("isClientId accepts UUID v4 only", () => {
    expect(core.isClientId(crypto.randomUUID())).toBe(true);
    expect(core.isClientId("123e4567-e89b-12d3-a456-426614174000")).toBe(false);
    expect(core.isClientId(null)).toBe(false);
  });

  test("validateReport builds a normalized body", () => {
    const { body, errors } = core.validateReport(valid);
    expect(body).toEqual({
      platform: "x",
      handle: "budi83749201",
      reason: "Posts identical campaign text",
      proofs: ["https://x.com/budi83749201/status/1"],
    });
    expect(errors.proofItems).toEqual([null, null]);
  });

  test("validateReport reports each field", () => {
    const { body, errors } = core.validateReport({
      platform: "x",
      handle: "bad handle",
      reason: "short",
      proofs: ["http://insecure.example", "https://" + "a".repeat(500), ""],
    });
    expect(body).toBeNull();
    expect(errors.handle).toContain("valid X handle");
    expect(errors.reason).toContain("at least 10");
    expect(errors.proofs).toBeNull();
    expect(errors.proofItems[0]).toContain("https://");
    expect(errors.proofItems[1]).toContain("500");
    expect(errors.proofItems[2]).toBeNull();
  });

  test("validateReport enforces proof count and reason length", () => {
    expect(core.validateReport({ ...valid, proofs: ["", " "] }).errors.proofs).toBe("Add at least one proof link.");
    const six = Array.from({ length: 6 }, (_, i) => `https://example.com/${i}`);
    expect(core.validateReport({ ...valid, proofs: six }).errors.proofs).toBe("Add at most 5 proof links.");
    expect(core.validateReport({ ...valid, reason: "a".repeat(1001) }).errors.reason).toContain("1000");
    expect(core.validateReport({ ...valid, platform: "tiktok" }).errors.platform).toBe("Choose X or Instagram.");
  });

  test("isProofUrl", () => {
    expect(core.isProofUrl("https://archive.org/x")).toBe(true);
    expect(core.isProofUrl("javascript:alert(1)")).toBe(false);
    expect(core.isProofUrl(undefined)).toBe(false);
  });

  test("reportsListPath encodes query", () => {
    expect(core.reportsListPath("x", null)).toBe("/v1/reports?platform=x&limit=50");
    expect(core.reportsListPath("instagram", "a b&c", 10)).toBe("/v1/reports?platform=instagram&limit=10&cursor=a+b%26c");
  });

  test("result and error messages", () => {
    expect(core.reportResultMessage(201)).toContain("new case");
    expect(core.reportResultMessage(200)).toContain("open case");
    expect(core.requestErrorMessage(403, "forbidden")).toBe("You reported this account");
    expect(core.requestErrorMessage(429)).toContain("Too many requests");
    expect(core.requestErrorMessage(409, "Already on the list")).toBe("Already on the list");
    expect(core.requestErrorMessage(500)).toBe("Server returned 500.");
  });

  test("applyVote updates counts and myVote without mutating", () => {
    const original = { id: 1, handle: "a", agree: 0, disagree: 0, myVote: null };
    const next = core.applyVote(original, { agree: 1, disagree: 0, myVote: "agree" });
    expect(next).toEqual({ id: 1, handle: "a", agree: 1, disagree: 0, myVote: "agree" });
    expect(original.myVote).toBeNull();
  });
});
