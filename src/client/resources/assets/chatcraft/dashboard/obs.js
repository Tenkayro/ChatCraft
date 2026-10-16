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
    if (item.badges?.length) {
      const badges = document.createElement("span");
      badges.className = "badges";
      for (const url of item.badges) {
        const image = document.createElement("img");
        image.className = "badge-image";
        image.src = url;
        image.alt = "";
        badges.append(image);
      }
      line.append(badges);
    }
    const name = text(`${item.user}:`, "name");
    name.style.color = item.color || "#9146ff";
    line.append(name);
    const message = document.createElement("span");
    message.className = "message";
    const words = item.message.split(" ");
    words.forEach((word, index) => {
      const url = data.emotes?.[word];
      if (url) {
        const image = document.createElement("img");
        image.className = "emote";
        image.src = url;
        image.alt = word;
        message.append(image);
      } else {
        message.append(document.createTextNode(`${index ? " " : ""}${word}`));
      }
    });
    line.append(message);
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
