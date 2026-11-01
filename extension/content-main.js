// 在 Melon 網頁裡執行：找出網頁上的播放器（<audio>/<video>），回報播放狀態。
// 只讀取播放狀態和歌名，不會讀取或傳送其他網頁內容。
(() => {
  if (window.__swtwInstalled) return;
  window.__swtwInstalled = true;

  const tracked = new Set();
  const lastTime = new WeakMap(); // 用來發現「明明在播卻卡住不動」
  const EVENTS = ["play", "playing", "pause", "waiting", "stalled", "ended", "error", "emptied", "seeking"];

  function track(el) {
    if (!el || tracked.has(el)) return;
    tracked.add(el);
    for (const ev of EVENTS) el.addEventListener(ev, () => report(ev), true);
    report("found");
  }

  // 有些播放器用 new Audio() 建立、沒有放進網頁，所以攔截 play()
  try {
    const origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) {
      track(this);
      return origPlay.apply(this, args);
    };
  } catch (_) {}

  // 放在網頁裡的播放器
  document.addEventListener("play", (e) => {
    if (e.target instanceof HTMLMediaElement) track(e.target);
  }, true);
  document.addEventListener("playing", (e) => {
    if (e.target instanceof HTMLMediaElement) track(e.target);
  }, true);

  function scan() {
    try {
      document.querySelectorAll("audio, video").forEach(track);
    } catch (_) {}
  }

  function computeState() {
    let state = null;
    const rank = { playing: 4, buffering: 3, paused: 2, ended: 1, error: 1 };
    const set = (s) => {
      if (!state || rank[s] > rank[state]) state = s;
    };
    for (const el of tracked) {
      // 太短的音效（例如按鈕聲）不算
      if (el.duration && isFinite(el.duration) && el.duration < 5) continue;
      if (el.error) { set("error"); continue; }
      if (el.ended) { set("ended"); continue; }
      if (el.paused) { set("paused"); continue; }
      const t = el.currentTime;
      const prev = lastTime.get(el);
      lastTime.set(el, { t, at: Date.now() });
      const stuck = prev && Date.now() - prev.at >= 4000 && Math.abs(t - prev.t) < 0.2;
      if (el.readyState < 3 || stuck) set("buffering");
      else set("playing");
    }
    return state;
  }

  function songInfo() {
    let title = null;
    let artist = null;
    try {
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      if (md) {
        title = md.title || null;
        artist = md.artist || null;
      }
    } catch (_) {}
    return { title, artist, pageTitle: document.title || "" };
  }

  let lastSent = "";
  let lastSentAt = 0;

  function report(reason) {
    const state = computeState();
    if (state === null && tracked.size === 0) return; // 這個頁面沒有播放器
    const info = songInfo();
    const key = state + "|" + info.title + "|" + info.artist;
    const now = Date.now();
    // 狀態沒變時，最多 15 秒回報一次，避免太頻繁
    if (key === lastSent && now - lastSentAt < 15000 && reason === "tick") return;
    lastSent = key;
    lastSentAt = now;
    window.postMessage({ __swtw: true, state, ...info, reason }, "*");
  }

  setInterval(() => {
    scan();
    report("tick");
  }, 5000);
  document.addEventListener("DOMContentLoaded", scan);
})();
