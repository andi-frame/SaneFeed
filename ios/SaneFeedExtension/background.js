"use strict";
importScripts("content/core.js");

const ext = globalThis.browser ?? globalThis.chrome;
const core = globalThis.SaneFeedCore;
const SYNC_ALARM = "sync-buzzers";
const SYNC_PERIOD_MINUTES = 6 * 60;

async function loadSettings() {
  const { settings } = await ext.storage.local.get("settings");
  return core.mergeSettings(settings);
}

// All count writes go through this chain so concurrent tabs cannot lose updates.
let countQueue = Promise.resolve();

function recordSeen(platform, key) {
  const run = async () => {
    const [{ counts }, settings] = await Promise.all([ext.storage.local.get("counts"), loadSettings()]);
    const limit = settings.dailyLimits[platform];
    const result = core.recordSeen(counts, platform, key, new Date(), limit);
    if (result.added || result.counts !== counts) {
      await ext.storage.local.set({ counts: result.counts });
    }
    return { count: result.count, limit };
  };
  const next = countQueue.then(run);
  countQueue = next.catch(() => {});
  return next;
}

/** Fetches the server list, using the stored ETag. Returns the stored list. */
async function syncBuzzers() {
  const [{ buzzerList }, settings] = await Promise.all([ext.storage.local.get("buzzerList"), loadSettings()]);
  const sameServer = buzzerList && buzzerList.serverUrl === settings.serverUrl;
  const headers = sameServer && buzzerList.etag ? { "If-None-Match": buzzerList.etag } : {};
  const syncedAt = new Date().toISOString();
  let next;
  try {
    const res = await fetch(`${settings.serverUrl}/v1/buzzers`, { headers, cache: "no-store" });
    if (res.status === 304 && sameServer) {
      next = { ...buzzerList, syncedAt, error: null };
    } else if (res.ok) {
      const parsed = core.parseBuzzerList(await res.json());
      next = {
        ...parsed,
        etag: res.headers.get("ETag"),
        serverUrl: settings.serverUrl,
        syncedAt,
        error: null,
      };
    } else {
      throw new Error(`Server returned ${res.status}`);
    }
  } catch (err) {
    // Keep the last good list; only record the failure.
    next = { ...(buzzerList || { version: 0, updatedAt: "", entries: [], etag: null }), error: err.message };
  }
  await ext.storage.local.set({ buzzerList: next });
  return next;
}

// One random id per install, sent as X-Client-Id. Cached so concurrent callers share it.
let clientIdPromise = null;

function getClientId() {
  clientIdPromise ??= ext.storage.local.get("clientId").then(async ({ clientId }) => {
    if (core.isClientId(clientId)) return clientId;
    const fresh = crypto.randomUUID();
    await ext.storage.local.set({ clientId: fresh });
    return fresh;
  });
  return clientIdPromise;
}

class RequestError extends Error {
  constructor(status, serverError) {
    super(core.requestErrorMessage(status, serverError));
    this.status = status;
  }
}

async function api(path, { method = "GET", body } = {}) {
  const [settings, clientId] = await Promise.all([loadSettings(), getClientId()]);
  const headers = { "X-Client-Id": clientId };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(settings.serverUrl + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  }).catch(() => {
    throw new Error("Could not reach the server. Check the server URL and your connection.");
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new RequestError(res.status, data.error);
  return { status: res.status, data };
}

const MY_REPORTS_MAX = 200;

/** Remembers cases this install reported, so the review list can disable voting on them up front. */
async function rememberOwnCase(caseId) {
  const { myReports = [] } = await ext.storage.local.get("myReports");
  if (myReports.includes(caseId)) return;
  await ext.storage.local.set({ myReports: [...myReports, caseId].slice(-MY_REPORTS_MAX) });
}

async function sendReport(form) {
  const { body } = core.validateReport(form);
  if (!body) throw new Error("Fix the highlighted fields.");
  const { status, data } = await api("/v1/reports", { method: "POST", body });
  await rememberOwnCase(data.caseId);
  return { caseId: data.caseId, message: core.reportResultMessage(status) };
}

async function listReports(platform, cursor) {
  const { data } = await api(core.reportsListPath(platform, cursor));
  return { cases: Array.isArray(data.cases) ? data.cases : [], nextCursor: data.nextCursor ?? null };
}

async function vote(caseId, choice) {
  if (!Number.isInteger(caseId) || (choice !== "agree" && choice !== "disagree")) {
    throw new Error("Invalid vote.");
  }
  try {
    const { data } = await api(`/v1/reports/${caseId}/votes`, { method: "POST", body: { vote: choice } });
    return data;
  } catch (err) {
    if (err.status === 403) await rememberOwnCase(caseId);
    throw err;
  }
}

function ensureAlarm() {
  ext.alarms.create(SYNC_ALARM, { delayInMinutes: SYNC_PERIOD_MINUTES, periodInMinutes: SYNC_PERIOD_MINUTES });
}

ext.runtime.onInstalled.addListener(() => {
  ensureAlarm();
  syncBuzzers();
});

ext.runtime.onStartup.addListener(async () => {
  if (!(await ext.alarms.get(SYNC_ALARM))) ensureAlarm();
  syncBuzzers();
});

ext.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === SYNC_ALARM) syncBuzzers();
});

const handlers = {
  seen: (msg) => recordSeen(msg.platform, msg.key),
  sync: () => syncBuzzers(),
  report: (msg) => sendReport(msg.form),
  listReports: (msg) => listReports(msg.platform, msg.cursor),
  vote: (msg) => vote(msg.caseId, msg.vote),
};

// sendResponse + `return true` works in both Safari and Chrome (Chrome ignores returned promises).
ext.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  const handler = msg && handlers[msg.type];
  if (!handler || (msg.platform !== undefined && !core.PLATFORMS.includes(msg.platform))) return false;
  handler(msg).then(
    (result) => sendResponse({ ok: true, result }),
    (err) => sendResponse({ ok: false, error: err.message, status: err.status ?? null })
  );
  return true;
});
