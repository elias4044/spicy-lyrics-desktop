chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (message?.type !== "spicy-playback" || sender.tab?.id == null) return false;
  chrome.storage.local.get(["spicyKey", "spicyTab"]).then(async ({ spicyKey, spicyTab }) => {
    if (!spicyKey || spicyTab !== sender.tab.id) return { stop: true };
    const response = await fetch("http://127.0.0.1:45687/browser", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer " + spicyKey },
      body: JSON.stringify({ ...message.track, tab: String(sender.tab.id) }),
      signal: AbortSignal.timeout(1500),
    });
    return response.ok ? response.json() : { error: response.status };
  }).then(reply).catch(() => reply({ error: "Desktop not available" }));
  return true;
});
