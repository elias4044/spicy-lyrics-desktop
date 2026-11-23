const key = document.querySelector("#key");
const status = document.querySelector("#status");
chrome.storage.local.get(["spicyKey", "spicyTab"]).then(({ spicyKey, spicyTab }) => {
  key.value = spicyKey || "";
  if (spicyTab != null) status.value = "A tab is linked.";
});
document.querySelector("#connect").onclick = async () => {
  const value = key.value.trim();
  if (!/^[a-zA-Z0-9_-]{43}$/.test(value)) {
    status.value = "Paste the key from Spicy Lyrics.";
    return;
  }
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  try {
    if (tab?.id == null || !/^https?:/.test(tab.url || "")) throw new Error();
    await chrome.storage.local.set({ spicyKey: value, spicyTab: tab.id });
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
    status.value = "Following this tab. Play a song.";
  } catch {
    await chrome.storage.local.remove("spicyTab");
    status.value = "Open the music website in this tab.";
  }
};
document.querySelector("#disconnect").onclick = async () => {
  await chrome.storage.local.remove("spicyTab");
  status.value = "Disconnected.";
};
