const session = document.querySelector('meta[name="chatcraft-session"]').content;
const $ = (id) => document.getElementById(id);
let currentToken = "";
let currentState = "";
let activeFilter = "all";
let lastEvents = [];
let activeConsoleLevel = "all";
let diagnostics = [];
let settingsLoaded = false;
let refreshRunning = false;
let consecutiveRefreshFailures = 0;
const recentDiagnostics = new Map();

const stateLabels = {
  CONNECTED: "Connected",
  WAITING: "Waiting",
  CONNECTING: "Connecting…",
  DISCONNECTED: "Disconnected",
  ERROR: "Error",
  AUTH_REQUIRED: "Authorization needed"
};

function showState(id, state) {
  const node = $(id);
  node.textContent = stateLabels[state] || state;
  node.className = `state-pill ${state.toLowerCase().replaceAll("_", "-")}`;
}

function reportDiagnostic(level, source, message) {
  const cleaned = String(message || "Unknown error").slice(0, 600);
  const key = `${level}:${source}:${cleaned}`;
  const now = Date.now();
  if (now - (recentDiagnostics.get(key) || 0) < 15000) return;
  recentDiagnostics.set(key, now);
  fetch("/api/diagnostics", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-ChatCraft-Session": session },
    body: JSON.stringify({ level, source, message: cleaned }),
    cache: "no-store"
  }).catch(() => {});
}

async function api(path, options = {}) {
  const headers = { "X-ChatCraft-Session": session, ...(options.headers || {}) };
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeout || 12000);
  if (options.method === "POST") {
    headers["Content-Type"] = "application/json";
  }
  try {
    const response = await fetch(path, { ...options, headers, signal: controller.signal, cache: "no-store" });
    const body = await response.text();
    let data;
    try {
      data = body ? JSON.parse(body) : {};
    } catch {
      throw new Error(`Invalid response from ${path} (HTTP ${response.status})`);
    }
    if (!response.ok) {
      const incident = data.incident ? ` · incident ${data.incident}` : "";
      const message = `${data.error || "Request failed"} (HTTP ${response.status}${incident})`;
      if (!path.startsWith("/api/diagnostics")) reportDiagnostic("error", `API ${path}`, message);
      throw new Error(message);
    }
    return data;
  } catch (error) {
    const message = error.name === "AbortError"
      ? `Request timed out after ${options.timeout || 12000} ms`
      : error.message;
    if (!path.startsWith("/api/diagnostics")) reportDiagnostic("error", `API ${path}`, message);
    throw new Error(message, { cause: error });
  } finally {
    clearTimeout(timeout);
  }
}

function setMessage(text, kind = "") {
  const node = $("oauthMessage");
  node.textContent = text;
  node.className = `message ${kind}`;
}

function setYoutubeMessage(text, kind = "") {
  const node = $("youtubeOauthMessage");
  node.textContent = text;
  node.className = `message ${kind}`;
}

function markCopied(button) {
  const original = button.innerHTML;
  button.innerHTML = '<i class="fa-solid fa-check"></i>';
  button.title = "Copied";
  setTimeout(() => {
    button.innerHTML = original;
    button.title = "Copy";
  }, 1400);
}

function renderStatus(data) {
  const state = data.twitchState || "DISCONNECTED";
  const youtubeState = data.youtubeState || "DISCONNECTED";
  const connected = [state, youtubeState].filter((item) => item === "CONNECTED").length;
  const hasError = [state, youtubeState].includes("ERROR");
  $("connectionBadge").textContent = `${connected} of 2 connected`;
  $("connectionBadge").className = `badge ${connected === 2 ? "connected" : hasError ? "error" : "neutral"}`;
  showState("connectionText", state);
  showState("youtubeConnectionText", youtubeState);
  $("queueText").textContent = data.queueSize;
  $("usernameText").textContent = data.username || "Not configured";
  $("controlsText").textContent = data.controlsEnabled ? "Enabled" : "Disabled";
  const eventState = data.eventSubState || "DISCONNECTED";
  $("tokenStatus").textContent = eventState === "AUTH_REQUIRED"
    ? "Authorize again"
    : data.tokenConfigured ? "Configured" : "Not configured";
  $("connectionDetail").textContent = eventState === "AUTH_REQUIRED"
    ? `${data.twitchDetail} · ${data.eventSubDetail}`
    : data.twitchDetail;
  $("youtubeConnectionDetail").textContent = data.youtubeDetail;
  $("youtubeTokenStatus").textContent = data.youtubeTokenConfigured ? "Configured" : "Not configured";
  $("youtubeRedirectUrl").textContent = data.youtubeRedirectUrl;
  $("redirectUrl").textContent = data.redirectUrl;
  $("lanUrl").textContent = data.lanUrl || "No private LAN address detected";
  $("copyLan").disabled = !data.lanUrl;
  $("obsUrl").textContent = data.obsUrl;
  if (!settingsLoaded && data.settings) {
    fillSettings(data.settings);
    settingsLoaded = true;
  }
  lastEvents = data.events || [];
  diagnostics = data.diagnostics || [];
  renderEvents();
  renderConsole();
}

async function readYoutubeOauthCallback() {
  if (location.pathname !== "/youtube/oauth/callback") return;
  const params = new URLSearchParams(location.search);
  if (params.has("error")) {
    setYoutubeMessage(params.get("error_description") || params.get("error"), "error");
    history.replaceState(null, "", "/");
    return;
  }
  const code = params.get("code");
  const state = params.get("state") || "";
  const expected = sessionStorage.getItem("chatcraft.youtube.oauth.state") || "";
  history.replaceState(null, "", "/");
  if (!code || !expected || state !== expected) {
    setYoutubeMessage("The YouTube authorization no longer matches. Start it again.", "error");
    return;
  }
  try {
    await api("/api/youtube/oauth/token", {
      method: "POST",
      body: JSON.stringify({ code, state })
    });
    sessionStorage.removeItem("chatcraft.youtube.oauth.state");
    setYoutubeMessage("YouTube was configured securely.", "success");
  } catch (error) {
    setYoutubeMessage(error.message, "error");
  }
}

function fillSettings(settings) {
  $("settingUsername").value = settings.username || "";
  $("settingPrefix").value = settings.commandPrefix || "";
  $("settingMovement").value = settings.movementDurationTicks;
  $("settingCamera").value = settings.cameraStepDegrees;
  $("settingWhitelist").value = (settings.whitelist || []).join("\n");
  $("settingBlacklist").value = (settings.blacklist || []).join("\n");
  $("settingControls").checked = settings.controlsEnabled;
  $("settingOverlay").checked = settings.overlayEnabled;
  $("settingAutoConnect").checked = settings.autoConnect;
  $("settingAutoReconnect").checked = settings.autoReconnect;
  $("settingKeepRunning").checked = settings.keepRunningUnfocused;
  $("settingFollowFirework").checked = settings.followFireworkEnabled;
  $("settingSubTnt").checked = settings.subTntEnabled;
  $("settingPrivilegedCommands").checked = settings.privilegedCommandsEnabled;
}

function nameList(value) {
  return [...new Set(value.split(/[\n,]+/).map((item) => item.trim().toLowerCase()).filter(Boolean))];
}

function renderEvents() {
  const body = $("activityBody");
  body.replaceChildren();
  const events = activeFilter === "all" ? lastEvents : lastEvents.filter((event) => event.category === activeFilter);
  if (!events.length) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 4;
    cell.className = "empty";
    cell.textContent = "No matching activity yet.";
    row.append(cell);
    body.append(row);
    return;
  }
  for (const event of events) {
    const row = document.createElement("tr");
    const time = document.createElement("td");
    time.textContent = new Date(event.timestamp).toLocaleTimeString();
    const type = document.createElement("td");
    const typeBadge = document.createElement("span");
    typeBadge.className = "type";
    typeBadge.textContent = event.category;
    type.append(typeBadge);
    const message = document.createElement("td");
    const main = document.createElement("div");
    main.className = "primary";
    main.textContent = event.user || (event.category === "system" ? "ChatCraft" : "—");
    const sub = document.createElement("div");
    sub.className = "secondary-text";
    sub.textContent = event.message || "—";
    message.append(main, sub);
    const action = document.createElement("td");
    const actionName = document.createElement("div");
    actionName.className = "primary";
    actionName.textContent = event.action || "—";
    const result = document.createElement("div");
    const bad = /error|blocked|full|cooldown|invalid|skipped|disabled|not a command/i.test(event.result);
    result.className = `secondary-text ${bad ? "result-bad" : "result-good"}`;
    result.textContent = event.result || "—";
    action.append(actionName, result);
    row.append(time, type, message, action);
    body.append(row);
  }
}

function renderConsole() {
  const body = $("consoleBody");
  body.replaceChildren();
  const visible = activeConsoleLevel === "all"
    ? diagnostics
    : diagnostics.filter((item) => item.level === activeConsoleLevel);
  const errors = diagnostics.filter((item) => item.level === "error").length;
  $("consoleCount").textContent = `${visible.length} ${visible.length === 1 ? "entry" : "entries"}`;
  $("consoleErrorCount").hidden = errors === 0;
  $("consoleErrorCount").textContent = `${errors} ${errors === 1 ? "error" : "errors"}`;
  if (!visible.length) {
    const empty = document.createElement("div");
    empty.className = "console-empty";
    const icon = document.createElement("i");
    icon.className = "fa-solid fa-check-circle";
    const label = document.createElement("span");
    label.textContent = activeConsoleLevel === "all" ? "No diagnostic messages yet." : "No matching messages.";
    empty.append(icon, label);
    body.append(empty);
    return;
  }
  for (const item of visible) {
    const line = document.createElement("div");
    line.className = `console-line ${item.level}`;
    const time = document.createElement("span");
    time.className = "console-time";
    time.textContent = new Date(item.timestamp).toLocaleTimeString();
    const level = document.createElement("span");
    level.className = "console-level";
    level.textContent = item.level;
    const source = document.createElement("span");
    source.className = "console-source";
    source.textContent = item.source || "ChatCraft";
    const message = document.createElement("span");
    message.className = "console-message";
    const diagnosticMessage = item.message || "—";
    if (diagnosticMessage.includes("\n")) {
      const [summary, ...detailLines] = diagnosticMessage.split("\n");
      const details = document.createElement("details");
      details.className = "console-details";
      const heading = document.createElement("summary");
      heading.textContent = summary;
      const detail = document.createElement("pre");
      detail.textContent = detailLines.join("\n");
      details.append(heading, detail);
      message.append(details);
    } else {
      message.textContent = diagnosticMessage;
    }
    line.append(time, level, source, message);
    body.append(line);
  }
}

async function refresh() {
  if (refreshRunning) return;
  refreshRunning = true;
  try {
    renderStatus(await api("/api/status", { timeout: 8000 }));
    consecutiveRefreshFailures = 0;
  } catch (error) {
    consecutiveRefreshFailures++;
    $("connectionDetail").textContent = `Dashboard API unavailable: ${error.message}`;
    $("connectionBadge").textContent = `Reconnecting (${consecutiveRefreshFailures})`;
    $("connectionBadge").className = "badge error";
  } finally {
    refreshRunning = false;
  }
}

function fillCredentials(credentials) {
  $("clientId").value = credentials.twitchClientId || "";
  $("twitchOauthToken").value = currentToken || credentials.twitchOauthToken || "";
  $("youtubeClientId").value = credentials.youtubeClientId || "";
  $("youtubeClientSecret").value = credentials.youtubeClientSecret || "";
  $("youtubeRefreshToken").value = credentials.youtubeRefreshToken || "";
}

async function loadCredentials() {
  fillCredentials(await api("/api/credentials"));
}

async function saveCredentials(platform, button, messageTarget) {
  button.disabled = true;
  try {
    const payload = platform === "twitch"
      ? {
          platform,
          twitchClientId: $("clientId").value.trim(),
          twitchOauthToken: $("twitchOauthToken").value.trim()
        }
      : {
          platform,
          youtubeClientId: $("youtubeClientId").value.trim(),
          youtubeClientSecret: $("youtubeClientSecret").value.trim(),
          youtubeRefreshToken: $("youtubeRefreshToken").value.trim()
        };
    const credentials = await api("/api/credentials", {
      method: "POST",
      body: JSON.stringify(payload)
    });
    fillCredentials(credentials);
    messageTarget("Credentials were encrypted and saved.", "success");
    await refresh();
  } catch (error) {
    messageTarget(error.message, "error");
  } finally {
    button.disabled = false;
  }
}

function readOauthCallback() {
  const params = new URLSearchParams(location.hash.slice(1));
  if (params.has("error")) {
    setMessage(params.get("error_description") || params.get("error"), "error");
    history.replaceState(null, "", "/oauth/callback");
    return;
  }
  if (!params.has("access_token")) return;
  const expected = sessionStorage.getItem("chatcraft.oauth.state") || "";
  currentState = params.get("state") || "";
  if (!expected || currentState !== expected) {
    setMessage("The Twitch authorization no longer matches. Start it again.", "error");
    history.replaceState(null, "", "/oauth/callback");
    return;
  }
  currentToken = params.get("access_token");
  $("twitchOauthToken").value = currentToken;
  $("tokenValue").textContent = "Token received from Twitch";
  $("tokenResult").hidden = false;
  setMessage("Twitch confirmed access. Save the token securely now.", "success");
  history.replaceState(null, "", "/oauth/callback");
}

$("oauthForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    const data = await api("/api/oauth/start", {
      method: "POST",
      body: JSON.stringify({ clientId: $("clientId").value.trim() })
    });
    sessionStorage.setItem("chatcraft.oauth.state", data.state);
    location.assign(data.url);
  } catch (error) {
    setMessage(error.message, "error");
    button.disabled = false;
  }
});

$("saveTwitchCredentials").addEventListener("click", () =>
  saveCredentials("twitch", $("saveTwitchCredentials"), setMessage));

$("saveToken").addEventListener("click", async () => {
  const button = $("saveToken");
  button.disabled = true;
  try {
    const data = await api("/api/oauth/token", {
      method: "POST",
      body: JSON.stringify({ token: currentToken, state: currentState })
    });
    $("twitchOauthToken").value = currentToken;
    currentToken = "";
    currentState = "";
    $("tokenValue").textContent = "Stored securely in ChatCraft";
    $("settingUsername").value = data.username;
    sessionStorage.removeItem("chatcraft.oauth.state");
    setMessage(`Twitch was configured for ${data.username}.`, "success");
    await refresh();
  } catch (error) {
    setMessage(error.message, "error");
    button.disabled = false;
  }
});

$("clearToken").addEventListener("click", async () => {
  if (!confirm("Remove Twitch authorization and disconnect?")) return;
  try {
    await api("/api/token/clear", { method: "POST", body: "{}" });
    $("tokenResult").hidden = true;
    currentToken = "";
    $("twitchOauthToken").value = "";
    setMessage("Twitch authorization was removed.", "success");
    await refresh();
  } catch (error) { setMessage(error.message, "error"); }
});

$("youtubeOauthForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    const data = await api("/api/youtube/oauth/start", {
      method: "POST",
      body: JSON.stringify({
        clientId: $("youtubeClientId").value.trim(),
        clientSecret: $("youtubeClientSecret").value.trim()
      })
    });
    sessionStorage.setItem("chatcraft.youtube.oauth.state", data.state);
    location.assign(data.url);
  } catch (error) {
    setYoutubeMessage(error.message, "error");
    button.disabled = false;
  }
});

$("saveYoutubeCredentials").addEventListener("click", () =>
  saveCredentials("youtube", $("saveYoutubeCredentials"), setYoutubeMessage));

$("clearYoutubeToken").addEventListener("click", async () => {
  if (!confirm("Remove YouTube authorization and disconnect?")) return;
  try {
    await api("/api/youtube/token/clear", { method: "POST", body: "{}" });
    $("youtubeRefreshToken").value = "";
    setYoutubeMessage("YouTube authorization was removed.", "success");
    await refresh();
  } catch (error) { setYoutubeMessage(error.message, "error"); }
});

$("settingsForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  const message = $("settingsMessage");
  try {
    const data = await api("/api/settings", {
      method: "POST",
      body: JSON.stringify({
        username: $("settingUsername").value.trim(),
        commandPrefix: $("settingPrefix").value,
        movementDurationTicks: Number($("settingMovement").value),
        cameraStepDegrees: Number($("settingCamera").value),
        whitelist: nameList($("settingWhitelist").value),
        blacklist: nameList($("settingBlacklist").value),
        controlsEnabled: $("settingControls").checked,
        overlayEnabled: $("settingOverlay").checked,
        autoConnect: $("settingAutoConnect").checked,
        autoReconnect: $("settingAutoReconnect").checked,
        keepRunningUnfocused: $("settingKeepRunning").checked,
        followFireworkEnabled: $("settingFollowFirework").checked,
        subTntEnabled: $("settingSubTnt").checked,
        privilegedCommandsEnabled: $("settingPrivilegedCommands").checked
      })
    });
    fillSettings(data.settings);
    message.textContent = "Saved";
    message.className = "message success";
    await refresh();
  } catch (error) {
    message.textContent = error.message;
    message.className = "message error";
  } finally {
    button.disabled = false;
  }
});

$("copyToken").addEventListener("click", async () => {
  if (currentToken) await navigator.clipboard.writeText(currentToken);
  markCopied($("copyToken"));
});
$("copyRedirect").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("redirectUrl").textContent);
  markCopied($("copyRedirect"));
});
$("copyYoutubeRedirect").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("youtubeRedirectUrl").textContent);
  markCopied($("copyYoutubeRedirect"));
});
$("copyLan").addEventListener("click", async () => {
  const url = $("lanUrl").textContent;
  if (!url.startsWith("https://")) return;
  await navigator.clipboard.writeText(url);
  markCopied($("copyLan"));
});
$("copyObs").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("obsUrl").textContent);
  markCopied($("copyObs"));
});

for (const button of document.querySelectorAll(".reveal-secret")) {
  button.addEventListener("click", () => {
    const input = $(button.dataset.secret);
    const reveal = input.type === "password";
    input.type = reveal ? "text" : "password";
    button.innerHTML = `<i class="fa-regular fa-eye${reveal ? "-slash" : ""}"></i>`;
    const action = reveal ? "Hide" : "Show";
    const label = button.getAttribute("aria-label").replace(/^(Show|Hide)/, action);
    button.title = label;
    button.setAttribute("aria-label", label);
  });
}

for (const button of document.querySelectorAll(".filter")) {
  button.addEventListener("click", () => {
    activeFilter = button.dataset.filter;
    document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item === button));
    renderEvents();
  });
}

for (const button of document.querySelectorAll(".console-filter")) {
  button.addEventListener("click", () => {
    activeConsoleLevel = button.dataset.level;
    document.querySelectorAll(".console-filter").forEach((item) => item.classList.toggle("active", item === button));
    renderConsole();
  });
}

$("clearConsole").addEventListener("click", async () => {
  const button = $("clearConsole");
  button.disabled = true;
  try {
    await api("/api/diagnostics/clear", { method: "POST", body: "{}" });
    diagnostics = [];
    await refresh();
  } catch (error) {
    reportDiagnostic("error", "Web panel", `Could not clear console: ${error.message}`);
  } finally {
    button.disabled = false;
  }
});

async function control(action) {
  try {
    await api("/api/control", { method: "POST", body: JSON.stringify({ action }) });
    setTimeout(refresh, 250);
  } catch (error) { $("connectionDetail").textContent = error.message; }
}
$("connectButton").addEventListener("click", () => control("connect"));
$("disconnectButton").addEventListener("click", () => control("disconnect"));
$("youtubeConnectButton").addEventListener("click", () => control("youtubeConnect"));
$("youtubeDisconnectButton").addEventListener("click", () => control("youtubeDisconnect"));
$("connectAllButton").addEventListener("click", () => control("connectAll"));
$("disconnectAllButton").addEventListener("click", () => control("disconnectAll"));

for (const button of document.querySelectorAll(".rule-button")) {
  button.addEventListener("click", async () => {
    button.disabled = true;
    const message = $("ruleMessage");
    try {
      const value = $(button.dataset.input).value === "true";
      await api("/api/gamerule", {
        method: "POST",
        body: JSON.stringify({ rule: button.dataset.rule, value })
      });
      message.textContent = `${button.dataset.rule} = ${value ? "on" : "off"} was requested.`;
      message.className = "message success";
    } catch (error) {
      message.textContent = error.message;
      message.className = "message error";
    } finally {
      button.disabled = false;
    }
  });
}

for (const button of document.querySelectorAll(".event-test")) {
  button.addEventListener("click", async () => {
    button.disabled = true;
    const message = $("eventMessage");
    try {
      await api("/api/event/test", {
        method: "POST",
        body: JSON.stringify({ event: button.dataset.event })
      });
      message.textContent = `${button.dataset.event === "follow" ? "Follow" : "Subscription"} test was queued.`;
      message.className = "message success";
      await refresh();
    } catch (error) {
      message.textContent = error.message;
      message.className = "message error";
    } finally {
      button.disabled = false;
    }
  });
}

window.addEventListener("error", (event) => {
  const stack = event.error?.stack ? `\n${event.error.stack}` : "";
  reportDiagnostic("error", "Web panel", `${event.message} (${event.filename || "inline"}:${event.lineno || 0})${stack}`);
});
window.addEventListener("unhandledrejection", (event) => {
  const reason = event.reason instanceof Error
    ? `${event.reason.message}${event.reason.stack ? `\n${event.reason.stack}` : ""}`
    : String(event.reason);
  reportDiagnostic("error", "Web panel promise", reason);
});

async function initialize() {
  readOauthCallback();
  await readYoutubeOauthCallback();
  try {
    await loadCredentials();
  } catch (error) {
    setMessage(`Could not load credentials: ${error.message}`, "error");
  }
  await refresh();
}

async function refreshLoop() {
  await refresh();
  setTimeout(refreshLoop, document.hidden ? 5000 : 1500);
}
initialize().finally(() => setTimeout(refreshLoop, 1500));
