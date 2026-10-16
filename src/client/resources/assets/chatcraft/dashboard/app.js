const session = document.querySelector('meta[name="chatcraft-session"]').content;
const $ = (id) => document.getElementById(id);
let currentToken = "";
let currentState = "";
let activeFilter = "all";
let lastEvents = [];
let settingsLoaded = false;

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

function renderStatus(data) {
  const state = data.twitchState || "DISCONNECTED";
  $("connectionBadge").textContent = state;
  $("connectionBadge").className = `badge ${state === "CONNECTED" ? "connected" : state === "ERROR" ? "error" : "neutral"}`;
  $("connectionText").textContent = state;
  $("queueText").textContent = data.queueSize;
  $("usernameText").textContent = data.username || "Not configured";
  $("controlsText").textContent = data.controlsEnabled ? "Enabled" : "Disabled";
  $("tokenStatus").textContent = data.tokenConfigured ? "Configured" : "Not configured";
  $("connectionDetail").textContent = data.twitchDetail;
  $("redirectUrl").textContent = data.redirectUrl;
  $("lanUrl").textContent = data.lanUrl || "No private LAN address detected";
  $("copyLan").disabled = !data.lanUrl;
  $("obsUrl").textContent = data.obsUrl;
  if (!$("clientId").value && data.clientId) $("clientId").value = data.clientId;
  if (!settingsLoaded && data.settings) {
    fillSettings(data.settings);
    settingsLoaded = true;
  }
  lastEvents = data.events || [];
  renderEvents();
}

function fillSettings(settings) {
  $("settingUsername").value = settings.username || "";
  $("settingPrefix").value = settings.commandPrefix || "";
  $("settingMovement").value = settings.movementDurationTicks;
  $("settingCamera").value = settings.cameraStepDegrees;
  $("settingQueue").value = settings.queueSize;
  $("settingCooldown").value = settings.cooldownMillis;
  $("settingWhitelist").value = (settings.whitelist || []).join("\n");
  $("settingBlacklist").value = (settings.blacklist || []).join("\n");
  $("settingControls").checked = settings.controlsEnabled;
  $("settingOverlay").checked = settings.overlayEnabled;
  $("settingAutoConnect").checked = settings.autoConnect;
  $("settingAutoReconnect").checked = settings.autoReconnect;
  $("settingKeepRunning").checked = settings.keepRunningUnfocused;
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
    setMessage("OAuth state mismatch. Start authorization again from this dashboard.", "error");
    history.replaceState(null, "", "/oauth/callback");
    return;
  }
  currentToken = params.get("access_token");
  $("tokenValue").textContent = currentToken;
  $("tokenResult").hidden = false;
  setMessage("Twitch returned an access token. Copy it or save it directly to ChatCraft.", "success");
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
    $("tokenValue").textContent = "Saved securely in ChatCraft configuration";
    $("settingUsername").value = data.username;
    sessionStorage.removeItem("chatcraft.oauth.state");
    setMessage(`Token validated and saved for ${data.username}.`, "success");
    await refresh();
  } catch (error) {
    setMessage(error.message, "error");
    button.disabled = false;
  }
});

$("clearToken").addEventListener("click", async () => {
  if (!confirm("Remove the saved Twitch access token and disconnect ChatCraft?")) return;
  try {
    await api("/api/token/clear", { method: "POST", body: "{}" });
    $("tokenResult").hidden = true;
    currentToken = "";
    setMessage("Saved Twitch token removed.", "success");
    await refresh();
  } catch (error) { setMessage(error.message, "error"); }
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
        queueSize: Number($("settingQueue").value),
        cooldownMillis: Number($("settingCooldown").value),
        whitelist: nameList($("settingWhitelist").value),
        blacklist: nameList($("settingBlacklist").value),
        controlsEnabled: $("settingControls").checked,
        overlayEnabled: $("settingOverlay").checked,
        autoConnect: $("settingAutoConnect").checked,
        autoReconnect: $("settingAutoReconnect").checked,
        keepRunningUnfocused: $("settingKeepRunning").checked
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
  $("copyToken").textContent = "Copied";
});
$("copyRedirect").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("redirectUrl").textContent);
  $("copyRedirect").textContent = "Copied";
});
$("copyLan").addEventListener("click", async () => {
  const url = $("lanUrl").textContent;
  if (!url.startsWith("https://")) return;
  await navigator.clipboard.writeText(url);
  $("copyLan").textContent = "Copied";
});
$("copyObs").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("obsUrl").textContent);
  $("copyObs").textContent = "Copied";
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
      message.textContent = `Requested ${button.dataset.rule} = ${value}. Check Minecraft chat for the result.`;
      message.className = "message success";
    } catch (error) {
      message.textContent = error.message;
      message.className = "message error";
    } finally {
      button.disabled = false;
    }
  });
}

readOauthCallback();
refresh();
setInterval(refresh, 1500);
