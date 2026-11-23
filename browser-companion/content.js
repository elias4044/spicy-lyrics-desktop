(() => {
  if (globalThis.spicyPlaybackInstalled) return;
  globalThis.spicyPlaybackInstalled = true;
  let requested = false;
  let coverRequest = null;
  const clean = (text) => text?.trim() || "";
  const siteText = (selector) => clean(document.querySelector(selector)?.textContent);
  const media = () => {
    const elements = [...document.querySelectorAll("audio,video")].filter(
      (item) => Number.isFinite(item.duration) && item.duration > 0 && !item.ended
    );
    return elements.find((item) => !item.paused && !item.muted) ??
      elements.find((item) => !item.paused) ?? elements[0];
  };
  const coverFor = (metadata, trackKey) => {
    const distance = (item) => Math.abs((parseInt(item.sizes, 10) || 512) - 256);
    const source = metadata?.artwork?.filter((item) => item.src)
      .sort((a, b) => distance(a) - distance(b))[0]?.src;
    if (coverRequest?.trackKey === trackKey && coverRequest.source === source) return coverRequest;
    const cover = { trackKey, source, sentAt: 0 };
    coverRequest = cover;
    if (!source) return cover;
    let url;
    try { url = new URL(source, document.baseURI); } catch { return cover; }
    if (!["https:", "http:", "blob:", "data:"].includes(url.protocol)) return cover;
    const image = new Image();
    image.crossOrigin = "anonymous";
    image.onload = () => {
      if (!image.naturalWidth || !image.naturalHeight) return;
      try {
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = 256;
        const size = Math.min(image.naturalWidth, image.naturalHeight);
        canvas.getContext("2d").drawImage(image, (image.naturalWidth - size) / 2,
          (image.naturalHeight - size) / 2, size, size, 0, 0, 256, 256);
        cover.jpeg = canvas.toDataURL("image/jpeg", 0.75).split(",")[1];
      } catch {
        // Some sites deliberately disallow cross-origin canvas reads.
      }
    };
    image.src = url.href;
    return cover;
  };
  const timer = setInterval(async () => {
    const audio = media();
    if (requested || !audio || !chrome.runtime?.id) return;
    const metadata = navigator.mediaSession?.metadata;
    const title = clean(metadata?.title) || siteText(".ytp-title-link") ||
      siteText('[data-testid="context-item-link"]') || clean(document.title);
    const artist = clean(metadata?.artist) || siteText("ytmusic-player-bar .byline a") ||
      siteText('[data-testid="context-item-info-artist"]');
    if (!title) return;
    requested = true;
    try {
      const album = clean(metadata?.album);
      const cover = coverFor(metadata, JSON.stringify([title, artist, album]));
      const jpeg = cover.jpeg && Date.now() - cover.sentAt > 5000 ? cover.jpeg : undefined;
      const result = await chrome.runtime.sendMessage({
        type: "spicy-playback",
        track: {
          title, artist, album, playing: !audio.paused, artwork: jpeg,
          duration: Math.round(audio.duration * 1000), position: Math.round(audio.currentTime * 1000),
          rate: audio.playbackRate, seek: audio.seekable.length > 0,
        },
      });
      if (jpeg && result && !result.error) cover.sentAt = Date.now();
      if (result?.stop) {
        clearInterval(timer);
        globalThis.spicyPlaybackInstalled = false;
      } else if (result?.action === "toggle") {
        if (audio.paused) await audio.play();
        else audio.pause();
      } else if (result?.action === "seek") {
        audio.currentTime = Math.max(0, Math.min(audio.duration, result.position / 1000));
      }
    } catch {
      if (!chrome.runtime?.id) clearInterval(timer);
    } finally {
      requested = false;
    }
  }, 420);
})();
