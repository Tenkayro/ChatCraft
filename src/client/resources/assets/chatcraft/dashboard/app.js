const session = document.querySelector('meta[name="chatcraft-session"]').content;
const $ = (id) => document.getElementById(id);
let currentToken = "";
let currentState = "";
let activeFilter = "all";
let lastEvents = [];
let settingsLoaded = false;
let setupStateLoaded = false;

const stateLabels = {
  CONNECTED: "Connected",
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

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.method === "POST") {
    headers["Content-Type"] = "application/json";
    headers["X-ChatCraft-Session"] = session;
  }
  const response = await fetch(path, { ...options, headers, cache: "no-store" });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
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
  if (!$("youtubeClientId").value && data.youtubeClientId) $("youtubeClientId").value = data.youtubeClientId;
  $("redirectUrl").textContent = data.redirectUrl;
  $("lanUrl").textContent = data.lanUrl || "No private LAN address detected";
  $("copyLan").disabled = !data.lanUrl;
  $("obsUrl").textContent = data.obsUrl;
  if (!$("clientId").value && data.clientId) $("clientId").value = data.clientId;
  if (!settingsLoaded && data.settings) {
    fillSettings(data.settings);
    settingsLoaded = true;
  }
  if (!setupStateLoaded) {
    $("twitchSetup").open = !data.tokenConfigured;
    $("youtubeSetup").open = !data.youtubeTokenConfigured;
    setupStateLoaded = true;
  }
  lastEvents = data.events || [];
  renderEvents();
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

async function refresh() {
  try { renderStatus(await api("/api/status")); }
  catch (error) { $("connectionDetail").textContent = error.message; }
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
  $("tokenValue").textContent = currentToken;
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

$("saveToken").addEventListener("click", async () => {
  const button = $("saveToken");
  button.disabled = true;
  try {
    const data = await api("/api/oauth/token", {
      method: "POST",
      body: JSON.stringify({ token: currentToken, state: currentState })
    });
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

$("clearYoutubeToken").addEventListener("click", async () => {
  if (!confirm("Remove YouTube authorization and disconnect?")) return;
  try {
    await api("/api/youtube/token/clear", { method: "POST", body: "{}" });
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

for (const button of document.querySelectorAll(".filter")) {
  button.addEventListener("click", () => {
    activeFilter = button.dataset.filter;
    document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item === button));
    renderEvents();
  });
}

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

readOauthCallback();
readYoutubeOauthCallback().then(refresh);
setInterval(refresh, 1500);
