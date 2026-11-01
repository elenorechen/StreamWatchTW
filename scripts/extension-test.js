// 在無頭 Chrome 裡測試刷歌守望擴充功能。
// 用假的 melon.com 網頁（攔截網址，不會真的連到 Melon）模擬：播放 → 暫停 → 通知 → 恢復 → 緩衝 → 關分頁。
// 用法：node scripts/extension-test.js <輸出資料夾>
const { chromium } = require("playwright");
const path = require("path");
const fs = require("fs");
const os = require("os");

const EXT = path.resolve(__dirname, "../extension");
const OUT = path.resolve(process.argv[2] || "ext-test-results");
fs.mkdirSync(OUT, { recursive: true });

const lines = [];
let fails = 0;
const log = (s) => { console.log(s); lines.push(s); };
const pass = (s) => log(`- ✅ ${s}`);
const fail = (s) => { log(`- ❌ ${s}`); fails++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function toneWav(seconds = 10, freq = 440) {
  const rate = 22050;
  const n = rate * seconds;
  const buf = Buffer.alloc(44 + n * 2);
  buf.write("RIFF", 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write("WAVE", 8);
  buf.write("fmt ", 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(rate, 24); buf.writeUInt32LE(rate * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write("data", 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 8000), 44 + i * 2);
  return buf;
}

const FAKE_PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>Melon 網頁播放器（測試）</title></head>
<body style="font:20px sans-serif;padding:40px">
<h1>假的 Melon 播放器</h1>
<audio id="a" src="https://www.melon.com/test/tone.wav" loop></audio>
<script>
  navigator.mediaSession.metadata = new MediaMetadata({ title: "Way 4 Luv", artist: "PLAVE" });
  document.getElementById("a").play();
</script></body></html>`;

(async () => {
  const userDir = fs.mkdtempSync(path.join(os.tmpdir(), "swtw-"));
  const ctx = await chromium.launchPersistentContext(userDir, {
    channel: "chromium",
    headless: true,
    viewport: { width: 1000, height: 700 },
    args: [
      `--disable-extensions-except=${EXT}`,
      `--load-extension=${EXT}`,
      "--autoplay-policy=no-user-gesture-required"
    ]
  });

  const wav = toneWav();
  await ctx.route("**://*.melon.com/**", async (route) => {
    const url = route.request().url();
    if (url.endsWith("tone.wav")) {
      return route.fulfill({ status: 200, contentType: "audio/wav", body: wav });
    }
    if (url.endsWith("hang.wav")) return; // 永遠載入不完：模擬網路卡住
    return route.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body: FAKE_PAGE });
  });

  let sw = ctx.serviceWorkers()[0];
  if (!sw) sw = await ctx.waitForEvent("serviceworker", { timeout: 20000 });
  const extId = sw.url().split("/")[2];
  log("# 刷歌守望 Chrome 擴充功能 測試報告");
  log("");
  log(`瀏覽器：Chromium ${ctx.browser() ? ctx.browser().version() : ""}`.trim());
  log("");

  // 攔截通知，記錄下來
  const installSpy = () => sw.evaluate(() => {
    if (self.__notesSpy) return;
    self.__notesSpy = true;
    self.__notes = [];
    const orig = chrome.notifications.create.bind(chrome.notifications);
    chrome.notifications.create = (id, opts, cb) => {
      self.__notes.push({ id, title: opts.title, message: opts.message, at: Date.now() });
      return orig(id, opts, cb);
    };
  });
  await installSpy();
  const notes = async () => { await installSpy(); return sw.evaluate(() => self.__notes); };
  const status = () => sw.evaluate(async () => {
    const local = await chrome.storage.local.get(null);
    const sess = await chrome.storage.session.get(null);
    const tabs = await chrome.tabs.query({ url: ["*://*.melon.com/*"] });
    return { local, rt: sess.rt, audible: tabs.map((t) => t.audible) };
  });
  async function waitFor(pred, timeoutMs, everyMs = 2000) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const v = await pred();
      if (v) return v;
      await sleep(everyMs);
    }
    return null;
  }
  const waitNote = (text, ms) => waitFor(async () => (await notes()).find((n) => n.title.includes(text)), ms);

  const popup = await ctx.newPage();
  await popup.setViewportSize({ width: 360, height: 900 });
  const shotPopup = async (name) => {
    await popup.goto(`chrome-extension://${extId}/popup.html`);
    await sleep(1500);
    await popup.screenshot({ path: path.join(OUT, name), fullPage: true });
    log(`  - 截圖：\`${name}\``);
  };

  // ---------- 1. 初始 ----------
  log("## 1. 安裝後");
  pass(`擴充功能載入成功（ID ${extId}）`);
  await shotPopup("01-popup-idle.png");

  await sw.evaluate(() => chrome.storage.local.set({ thresholdSec: 15, repeatMin: 1, maxAlerts: 2, active: true }));

  // ---------- 2. 播放中 ----------
  log("");
  log("## 2. 開啟 Melon 並播放");
  const melon = await ctx.newPage();
  await melon.goto("https://www.melon.com/webplayer/test.htm");
  const st2 = await waitFor(async () => {
    const s = await status();
    return s.rt && s.rt.state === "playing" ? s : null;
  }, 30000);
  if (st2) {
    pass(`偵測到「播放中」：${st2.rt.detail}（分頁 audible=${st2.audible}）`);
    if ((st2.rt.detail || "").includes("Way 4 Luv")) pass("讀到歌名");
    else fail("沒有讀到歌名");
  } else {
    const s = await status();
    fail(`30 秒內沒有變成播放中（目前：${s.rt && s.rt.state} / ${s.rt && s.rt.detail}）`);
  }
  await shotPopup("02-popup-playing.png");

  // ---------- 3. 暫停 ----------
  log("");
  log("## 3. 暫停 → 15 秒後通知");
  const t0 = Date.now();
  await melon.evaluate(() => document.getElementById("a").pause());
  const st3 = await waitFor(async () => {
    const s = await status();
    return s.rt && s.rt.state === "stopped" ? s : null;
  }, 20000, 1000);
  if (st3) pass(`狀態變成「停止」（${Math.round((Date.now() - t0) / 1000)} 秒內）：${st3.rt.detail}`);
  else fail("暫停後狀態沒有變成停止");
  await shotPopup("03-popup-stopped.png");
  const n3 = await waitNote("Melon 停止了", 75000);
  if (n3) pass(`跳出提醒「${n3.title}」，暫停後 ${Math.round((n3.at - t0) / 1000)} 秒：${n3.message}`);
  else fail("暫停後 75 秒內沒有跳出提醒");

  // ---------- 4. 恢復 ----------
  log("");
  log("## 4. 恢復播放 → 通知已恢復");
  await melon.evaluate(() => document.getElementById("a").play());
  const n4 = await waitNote("恢復播放", 30000);
  if (n4) pass(`收到「${n4.title}」：${n4.message}`);
  else fail("恢復播放後沒有收到恢復通知");

  // ---------- 5. 緩衝卡住 ----------
  log("");
  log("## 5. 網路卡住（一直緩衝）→ 通知");
  const t5 = Date.now();
  await melon.evaluate(() => {
    const a = document.getElementById("a");
    a.src = "https://www.melon.com/test/hang.wav";
    a.play().catch(() => {});
  });
  const st5 = await waitFor(async () => {
    const s = await status();
    return s.rt && s.rt.state === "buffering" ? s : null;
  }, 30000, 1000);
  if (st5) pass("狀態變成「緩衝中」");
  else { const s = await status(); fail(`沒有變成緩衝中（目前：${s.rt && s.rt.state}）`); }
  const n5 = await waitFor(async () => (await notes()).find((n) => n.title.includes("緩衝中了") && n.at > t5), 75000);
  if (n5) pass(`跳出提醒「${n5.title}」，${Math.round((n5.at - t5) / 1000)} 秒後`);
  else fail("緩衝 75 秒內沒有跳出提醒");

  // 恢復正常播放
  await melon.evaluate(() => { const a = document.getElementById("a"); a.src = "https://www.melon.com/test/tone.wav"; a.play(); });
  await waitFor(async () => { const s = await status(); return s.rt && s.rt.state === "playing"; }, 30000);

  // ---------- 6. 關掉分頁 ----------
  log("");
  log("## 6. 關掉 Melon 分頁 → 無法確認 → 通知");
  const t6 = Date.now();
  await melon.close();
  const st6 = await waitFor(async () => { const s = await status(); return s.rt && s.rt.state === "unknown" ? s : null; }, 15000, 1000);
  if (st6) pass(`狀態變成「無法確認」：${st6.rt.detail}`);
  else fail("關掉分頁後狀態沒有變成無法確認");
  const n6 = await waitFor(async () => (await notes()).find((n) => n.title.includes("無法確認了") && n.at > t6), 75000);
  if (n6) pass(`跳出提醒「${n6.title}」，${Math.round((n6.at - t6) / 1000)} 秒後`);
  else fail("關掉分頁 75 秒內沒有跳出提醒");
  await shotPopup("04-popup-closed.png");

  // ---------- 7. 停止監控 ----------
  log("");
  log("## 7. 停止監控");
  await sw.evaluate(() => chrome.storage.local.set({ active: false }));
  await sw.evaluate(() => chrome.alarms.getAll()).then((a) => log(`  - 排程：${a.map((x) => x.name).join(", ")}`));
  pass("測試結束");

  log("");
  log(fails === 0 ? "**結果：全部通過**" : `**結果：有 ${fails} 項沒通過**`);
  log("");
  log("### 所有通知");
  for (const n of await notes()) log(`- ${new Date(n.at).toISOString().slice(11, 19)} ${n.title} — ${n.message}`);
  fs.writeFileSync(path.join(OUT, "report.md"), lines.join("\n") + "\n");
  await ctx.close();
  process.exit(fails === 0 ? 0 : 1);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
