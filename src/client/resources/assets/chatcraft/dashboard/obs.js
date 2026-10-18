const chat = document.getElementById("chat");

function text(value, className) {
  const node = document.createElement("span");
  node.className = className;
  node.textContent = value;
  return node;
}

function render(data) {
  chat.replaceChildren();
  for (const item of (data.chat || []).slice(-8)) {
    const line = document.createElement("div");
    line.className = "line";
    const name = text(`${item.source === "YouTube" ? "▶ " : ""}${item.user}`, "name");
    name.style.color = item.color || "#9146ff";
    line.append(name);
    line.append(text(" \u00BB ", "separator"));
    line.append(text(item.message, "message"));
    chat.append(line);
  }
}

async function refresh() {
  try {
    const response = await fetch("/api/status", { cache: "no-store" });
    if (response.ok) render(await response.json());
  } catch (_) { }
}

refresh();
setInterval(refresh, 1000);
