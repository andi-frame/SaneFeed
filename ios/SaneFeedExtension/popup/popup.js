(() => {
  "use strict";
  const ext = globalThis.browser ?? globalThis.chrome;
  const core = globalThis.SaneFeedCore;
  const $ = (id) => document.getElementById(id);

  function el(tag, props, ...children) {
    const node = Object.assign(document.createElement(tag), props);
    node.append(...children);
    return node;
  }

  function showError(id, message) {
    const node = $(id);
    node.textContent = message || "";
    node.hidden = !message;
  }

  async function send(message) {
    const res = await ext.runtime.sendMessage(message);
    if (!res) throw new Error("The extension did not respond. Try again.");
    if (!res.ok) throw Object.assign(new Error(res.error), { status: res.status });
    return res.result;
  }

  // ---- Tabs ----------------------------------------------------------------

  const tabs = [...document.querySelectorAll('[role="tab"]')];
  for (const tab of tabs) {
    tab.addEventListener("click", () => {
      for (const other of tabs) {
        const selected = other === tab;
        other.setAttribute("aria-selected", String(selected));
        $(other.getAttribute("aria-controls")).hidden = !selected;
      }
      if (tab.id === "tab-review" && !review.loaded) loadCases();
    });
  }

  // ---- Settings ------------------------------------------------------------

  let settings = core.mergeSettings();
  let buzzerList = null;
  let counts = null;

  async function saveSettings(patch) {
    settings = core.mergeSettings({ ...settings, ...patch });
    await ext.storage.local.set({ settings });
    renderSettings();
  }

  function renderToday() {
    const now = new Date();
    $("today").replaceChildren(
      ...core.PLATFORMS.map((platform) => {
        const count = core.countFor(counts, platform, now);
        const limit = settings.dailyLimits[platform];
        const fill = el("span", { className: "meter-fill" });
        fill.style.width = `${Math.min(100, (count / limit) * 100)}%`;
        return el(
          "div",
          { className: "row row-stack" },
          el(
            "div",
            { className: "row-line" },
            el("span", { textContent: core.PLATFORM_LABELS[platform] }),
            el("span", { className: "value", textContent: core.progressLabel(count, limit) })
          ),
          el("span", { className: "meter" }, fill)
        );
      })
    );
  }

  function renderManualList() {
    const rows = settings.manualList.map((entry) => {
      const remove = el("button", { type: "button", className: "link danger", textContent: "Remove" });
      remove.setAttribute("aria-label", `Remove @${entry.handle} on ${core.PLATFORM_LABELS[entry.platform]}`);
      remove.addEventListener("click", () =>
        saveSettings({
          manualList: settings.manualList.filter((e) => e.platform !== entry.platform || e.handle !== entry.handle),
        })
      );
      return el(
        "li",
        { className: "row" },
        el(
          "span",
          {},
          `@${entry.handle} `,
          el("span", { className: "muted", textContent: core.PLATFORM_LABELS[entry.platform] })
        ),
        remove
      );
    });
    $("manual-list").replaceChildren(
      ...(rows.length ? rows : [el("li", { className: "row muted", textContent: "No accounts added." })])
    );
  }

  function renderServerList() {
    const entries = buzzerList?.entries?.length ?? 0;
    $("list-summary").textContent = buzzerList?.version
      ? `${entries} accounts · version ${buzzerList.version}`
      : "Not downloaded yet";
    const parts = [];
    if (buzzerList?.syncedAt) parts.push(`Checked ${new Date(buzzerList.syncedAt).toLocaleString()}.`);
    if (buzzerList?.error) parts.push(`Last sync failed: ${buzzerList.error}`);
    parts.push("Checks every 6 hours.");
    $("sync-status").textContent = parts.join(" ");
  }

  function renderSettings() {
    for (const platform of core.PLATFORMS) $(`limit-${platform}`).value = settings.dailyLimits[platform];
    $("block-reels").checked = settings.blockReels;
    $("heuristic").checked = settings.heuristic;
    if (document.activeElement !== $("server-url")) $("server-url").value = settings.serverUrl;
    renderToday();
    renderManualList();
  }

  for (const platform of core.PLATFORMS) {
    $(`limit-${platform}`).addEventListener("change", (event) => {
      const limit = core.clampLimit(event.target.value);
      if (limit === null) {
        event.target.value = settings.dailyLimits[platform];
        return;
      }
      saveSettings({ dailyLimits: { ...settings.dailyLimits, [platform]: limit } });
    });
  }

  $("block-reels").addEventListener("change", (event) => saveSettings({ blockReels: event.target.checked }));
  $("heuristic").addEventListener("change", (event) => saveSettings({ heuristic: event.target.checked }));

  $("manual-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const platform = $("manual-platform").value;
    const handle = core.parseHandle(platform, $("manual-handle").value);
    if (!handle) {
      showError("manual-error", `Enter a valid ${core.PLATFORM_LABELS[platform]} handle.`);
      return;
    }
    showError("manual-error", "");
    $("manual-handle").value = "";
    if (settings.manualList.some((e) => e.platform === platform && e.handle === handle)) return;
    saveSettings({ manualList: [...settings.manualList, { platform, handle }] });
  });

  async function syncNow() {
    const button = $("sync-now");
    button.disabled = true;
    button.textContent = "Syncing";
    try {
      buzzerList = await send({ type: "sync" });
      renderServerList();
    } catch (err) {
      $("sync-status").textContent = err.message;
    } finally {
      button.disabled = false;
      button.textContent = "Sync now";
    }
  }

  $("sync-now").addEventListener("click", syncNow);

  $("server-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const url = core.normalizeServerUrl($("server-url").value);
    if (!url) {
      showError("server-error", "Enter a full URL starting with https://");
      return;
    }
    showError("server-error", "");
    // Must run inside the user gesture. If declined, requests still work when the server allows CORS.
    if (ext.permissions?.request) {
      await ext.permissions.request({ origins: [`${new URL(url).origin}/*`] }).catch(() => false);
    }
    $("server-url").blur();
    await saveSettings({ serverUrl: url });
    syncNow();
  });

  // ---- Report --------------------------------------------------------------

  let reportAttempted = false;

  function proofInputs() {
    return [...$("proof-list").querySelectorAll("input")];
  }

  function addProofRow(value = "") {
    const input = el("input", {
      type: "url",
      value,
      placeholder: "https://",
      autocomplete: "off",
      autocapitalize: "off",
      spellcheck: false,
    });
    input.setAttribute("aria-label", "Proof link");
    input.addEventListener("input", () => reportAttempted && validateReportForm());
    const remove = el("button", { type: "button", className: "link danger", textContent: "Remove" });
    const error = el("p", { className: "field-error", hidden: true });
    const row = el("li", { className: "proof" }, el("div", { className: "row row-form" }, input, remove), error);
    remove.addEventListener("click", () => {
      row.remove();
      updateProofControls();
      if (reportAttempted) validateReportForm();
    });
    $("proof-list").append(row);
    updateProofControls();
    return input;
  }

  function updateProofControls() {
    const rows = $("proof-list").children;
    $("proof-add").hidden = rows.length >= core.PROOFS_MAX;
    for (const row of rows) row.querySelector("button").hidden = rows.length === 1;
  }

  function readReportForm() {
    return {
      platform: $("report-platform").value,
      handle: $("report-handle").value,
      reason: $("report-reason").value,
      proofs: proofInputs().map((input) => input.value),
    };
  }

  function validateReportForm() {
    const { body, errors } = core.validateReport(readReportForm());
    showError("report-handle-error", errors.handle);
    showError("report-reason-error", errors.reason);
    showError("report-proofs-error", errors.proofs);
    [...$("proof-list").children].forEach((row, i) => {
      const error = row.querySelector(".field-error");
      error.textContent = errors.proofItems[i] || "";
      error.hidden = !errors.proofItems[i];
    });
    return body;
  }

  function resetReportForm() {
    reportAttempted = false;
    $("report-handle").value = "";
    $("report-reason").value = "";
    $("proof-list").replaceChildren();
    addProofRow();
    updateReasonCount();
    for (const id of ["report-handle-error", "report-reason-error", "report-proofs-error"]) showError(id, "");
  }

  function updateReasonCount() {
    $("report-reason-count").textContent = `${$("report-reason").value.trim().length} of ${core.REASON_MAX} characters`;
  }

  $("report-reason").addEventListener("input", () => {
    updateReasonCount();
    if (reportAttempted) validateReportForm();
  });
  for (const id of ["report-handle", "report-platform"]) {
    $(id).addEventListener("input", () => reportAttempted && validateReportForm());
  }
  $("proof-add").addEventListener("click", () => addProofRow().focus());

  $("report-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    reportAttempted = true;
    const status = $("report-status");
    status.textContent = "";
    if (!validateReportForm()) return;
    const submit = $("report-submit");
    submit.disabled = true;
    submit.textContent = "Sending";
    try {
      const result = await send({ type: "report", form: readReportForm() });
      resetReportForm();
      status.textContent = result.message;
      review.loaded = false;
    } catch (err) {
      status.textContent = err.message;
    } finally {
      submit.disabled = false;
      submit.textContent = "Send report";
    }
  });

  // ---- Review --------------------------------------------------------------

  const review = { loaded: false, cases: [], nextCursor: null, ownCases: new Set() };

  function plural(n, word) {
    return `${n} ${word}${n === 1 ? "" : "s"}`;
  }

  function renderCase(reportCase, error = "") {
    const own = review.ownCases.has(reportCase.id);
    const proofs = Array.isArray(reportCase.proofs) ? reportCase.proofs : [];
    const reasons = [...new Set(proofs.map((p) => p.reason).filter(Boolean))];
    const links = proofs.filter((p) => core.isProofUrl(p.url));

    const item = el(
      "li",
      { className: "case" },
      el(
        "div",
        { className: "case-head" },
        el("strong", { textContent: `@${reportCase.handle}` }),
        el("span", {
          className: "muted",
          textContent: `${plural(reportCase.reporters, "reporter")} · ${reportCase.agree} agree · ${reportCase.disagree} disagree`,
        })
      )
    );
    if (reasons.length) {
      item.append(el("ul", { className: "case-reasons" }, ...reasons.map((r) => el("li", { textContent: r }))));
    }
    if (links.length) {
      item.append(
        el(
          "ul",
          { className: "case-links" },
          ...links.map((p) =>
            el("li", {}, el("a", { href: p.url, target: "_blank", rel: "noopener noreferrer", textContent: p.url }))
          )
        )
      );
    }

    const buttons = ["agree", "disagree"].map((choice) => {
      const button = el("button", {
        type: "button",
        className: "vote",
        textContent: choice === "agree" ? "Agree" : "Disagree",
        disabled: own,
      });
      button.setAttribute("aria-pressed", String(reportCase.myVote === choice));
      button.addEventListener("click", () => castVote(reportCase, choice, item));
      return button;
    });
    item.append(el("div", { className: "case-actions" }, ...buttons));
    if (own) item.append(el("p", { className: "note-inline", textContent: "You reported this account" }));
    if (error) item.append(el("p", { className: "field-error", textContent: error }));
    return item;
  }

  async function castVote(reportCase, choice, item) {
    for (const button of item.querySelectorAll(".vote")) button.disabled = true;
    let next = reportCase;
    let error = "";
    try {
      next = core.applyVote(reportCase, await send({ type: "vote", caseId: reportCase.id, vote: choice }));
    } catch (err) {
      if (err.status === 403) review.ownCases.add(reportCase.id);
      else error = err.message;
    }
    review.cases = review.cases.map((c) => (c.id === next.id ? next : c));
    item.replaceWith(renderCase(next, error));
  }

  function renderCases() {
    $("case-list").replaceChildren(...review.cases.map((c) => renderCase(c)));
    $("review-more").hidden = !review.nextCursor;
    if (!review.cases.length) $("review-status").textContent = "No pending reports.";
  }

  async function loadCases(more = false) {
    const status = $("review-status");
    status.textContent = "Loading";
    $("review-more").disabled = true;
    try {
      const platform = $("review-platform").value;
      const [page, stored] = await Promise.all([
        send({ type: "listReports", platform, cursor: more ? review.nextCursor : null }),
        ext.storage.local.get("myReports"),
      ]);
      review.ownCases = new Set(stored.myReports || []);
      review.cases = more ? [...review.cases, ...page.cases] : page.cases;
      review.nextCursor = page.nextCursor;
      review.loaded = true;
      status.textContent = "";
      renderCases();
    } catch (err) {
      status.textContent = err.message;
    } finally {
      $("review-more").disabled = false;
    }
  }

  $("review-platform").addEventListener("change", () => loadCases());
  $("review-more").addEventListener("click", () => loadCases(true));

  // ---- Startup -------------------------------------------------------------

  ext.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;
    if (changes.counts) {
      counts = changes.counts.newValue;
      renderToday();
    }
    if (changes.buzzerList) {
      buzzerList = changes.buzzerList.newValue;
      renderServerList();
    }
  });

  ext.storage.local.get(["settings", "buzzerList", "counts"]).then((stored) => {
    settings = core.mergeSettings(stored.settings);
    buzzerList = stored.buzzerList ?? null;
    counts = stored.counts ?? null;
    renderSettings();
    renderServerList();
    resetReportForm();
  });
})();
