const $ = (id) => document.getElementById(id);

const CHIP = {
  playing: ["播放中", "#DCEFD9", "#2E6B34"],
  buffering: ["緩衝中", "#ECE8E1", "#6B6457"],
  stopped: ["停止", "#F8D9CC", "#9A3F1F"],
  unknown: ["無法確認", "#E9E6E1", "#5E5A54"],
  paused: ["暫停監控", "#E6E0F3", "#4B3F72"],
  idle: ["等待開始", "#ECEAE6", "#6B6B6B"]
};

function send(cmd, value) {
  return chrome.runtime.sendMessage({ cmd, value });
}

function fmt(sec) {
  sec = Math.max(0, Math.round(sec));
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`;
}

function fmtHm(sec) {
  sec = Math.max(0, Math.round(sec));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return h > 0 ? `${h} 小時 ${m} 分` : m > 0 ? `${m} 分鐘` : `${sec} 秒`;
}

let settingsLoaded = false;
let last = null;

function toast(msg) {
  $("toast").textContent = msg;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => ($("toast").textContent = ""), 4000);
}

function render({ rt, s }) {
  last = { rt, s };
  const active = rt.active;
  const master = $("master");
  master.textContent = active ? "■ 停止監控" : "▶ 開始監控";
  master.style.background = active ? "#4B3F72" : "#4E9A5B";
  $("masterHint").textContent = active ? "監控中 · 每 30 秒以內檢查一次" : "目前沒有在監控";

  const [label, bg, fg] = CHIP[rt.state] || CHIP.idle;
  const chip = $("chip");
  chip.textContent = label;
  chip.style.background = bg;
  chip.style.color = fg;

  $("tabCount").textContent = rt.tabCount ? `（${rt.tabCount} 個分頁）` : "";

  let detail = rt.detail || "";
  if (rt.state === "idle") detail = "按「開始監控」後會顯示狀態";
  else if (rt.state === "paused") detail = "暫停監控中，不會通知";
  else if (rt.badSince) {
    const elapsed = (Date.now() - rt.badSince) / 1000;
    if (rt.acked) detail += ` · 已按「知道了」`;
    else if (rt.alertsSent === 0) {
      const left = s.thresholdSec - elapsed;
      detail += ` · 已持續 ${fmt(elapsed)}` + (left > 0 ? ` · 約 ${fmt(left)}後通知` : " · 即將通知");
    } else {
      detail += ` · 已持續 ${fmt(elapsed)} · 已通知 ${rt.alertsSent}/${s.maxAlerts} 次`;
    }
  }
  $("detail").textContent = detail;

  $("pause").textContent = rt.monitorPaused ? "恢復監控" : "暫停監控";
  $("pause").disabled = !active;
  $("pause").style.opacity = active ? 1 : 0.5;
  $("autoResume").checked = rt.autoResume;

  if (!settingsLoaded) {
    settingsLoaded = true;
    $("thresholdSec").value = String(s.thresholdSec);
    $("repeatMin").value = String(s.repeatMin);
    $("maxAlerts").value = String(s.maxAlerts);
    $("notifyRecovery").checked = s.notifyRecovery;
    $("remoteEnabled").checked = s.remoteEnabled;
    $("deviceName").value = s.deviceName;
    $("topic").value = s.topic;
  }
}

async function refresh() {
  try {
    const res = await send("status");
    if (res && res.rt) render(res);
    const st = await send("stats");
    if (st && st.date) {
      $("sPlay").textContent = fmtHm(st.playSec);
      $("sLongest").textContent = fmtHm(st.longestSec);
      $("sInt").textContent = `${st.interrupts} 次`;
      $("sStreak").textContent = `${st.streakDays} 天`;
    }
  } catch (_) {}
}

$("openCard").addEventListener("click", () => send("openCard"));

$("master").addEventListener("click", async () => {
  await send("setActive", !(last && last.rt.active));
  refresh();
});
$("pause").addEventListener("click", async () => {
  await send("setPaused", !(last && last.rt.monitorPaused));
  refresh();
});
$("autoResume").addEventListener("change", (e) => send("setAutoResume", e.target.checked));
$("focus").addEventListener("click", () => send("focusMelon"));

for (const id of ["thresholdSec", "repeatMin", "maxAlerts"]) {
  $(id).addEventListener("change", (e) => send("saveSettings", { [id]: Number(e.target.value) }));
}
for (const id of ["notifyRecovery", "remoteEnabled"]) {
  $(id).addEventListener("change", (e) => send("saveSettings", { [id]: e.target.checked }));
}
$("deviceName").addEventListener("change", (e) => {
  send("saveSettings", { deviceName: e.target.value.trim() || "刷歌電腦" });
});
$("topic").addEventListener("change", (e) => {
  const v = e.target.value.trim();
  if (!/^[A-Za-z0-9_-]{6,64}$/.test(v)) {
    toast("代碼格式不對：只能有英文、數字、- 和 _");
    e.target.value = last ? last.s.topic : "";
    return;
  }
  send("saveSettings", { topic: v });
  if (last) last.s.topic = v;
  toast("已儲存連結代碼");
});
$("copyTopic").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("topic").value);
  toast("已複製代碼");
});
$("newTopic").addEventListener("click", async () => {
  if (!confirm("產生新代碼後，舊代碼會失效，手機要重新訂閱。確定嗎？")) return;
  const { topic } = await send("newTopic");
  $("topic").value = topic;
  if (last) last.s.topic = topic;
  toast("已產生新代碼");
});
$("testLocal").addEventListener("click", () => send("testLocal"));
$("testRemote").addEventListener("click", async () => {
  toast("送出中…");
  const r = await send("testRemote");
  toast(r && r.ok ? "測試通知已送出，去手機看看" : (r && r.msg) || "送出失敗");
});

refresh();
setInterval(refresh, 1000);
