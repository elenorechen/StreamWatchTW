// 刷瓜小幫手（Melon 網頁版）背景程式：判斷 Melon 有沒有在播放，時間到了就通知。

const MELON_URLS = ["*://*.melon.com/*"];
const DEFAULT_SERVER = "https://ntfy.sh";
const TOPIC_CHARS = "abcdefghijkmnpqrstuvwxyz23456789";
const ALERT_ID = "swtw-alert";
const INFO_ID = "swtw-info";

const DEFAULTS = {
  thresholdSec: 30,
  repeatMin: 5,
  maxAlerts: 2,
  notifyRecovery: true,
  remoteEnabled: false,
  deviceName: "刷歌電腦",
  ntfyServer: DEFAULT_SERVER,
  topic: null
};

const LABELS = {
  playing: "播放中",
  buffering: "緩衝中",
  stopped: "停止",
  unknown: "無法確認",
  paused: "暫停監控",
  idle: "等待開始"
};

// ---------- 儲存 ----------

async function getSettings() {
  const s = await chrome.storage.local.get(DEFAULTS);
  if (!s.topic) {
    s.topic = newTopic();
    await chrome.storage.local.set({ topic: s.topic });
  }
  return s;
}

function newTopic() {
  const buf = new Uint32Array(20);
  crypto.getRandomValues(buf);
  let t = "plli-";
  for (const n of buf) t += TOPIC_CHARS[n % TOPIC_CHARS.length];
  return t;
}

const RT_DEFAULTS = {
  active: false,
  monitorPaused: false,
  autoResume: true,
  badSince: 0,
  badState: null,
  alertsSent: 0,
  lastAlertAt: 0,
  acked: false,
  state: "idle",
  detail: "",
  song: null,
  tabs: {} // tabId -> { frames: { frameId: {state,title,artist,at} }, url, title }
};

async function getRt() {
  // active 存在 local，瀏覽器重開後還記得；其餘執行中狀態放 session
  const local = await chrome.storage.local.get({ active: false, monitorPaused: false, autoResume: true });
  const sess = await chrome.storage.session.get({ rt: null });
  return { ...RT_DEFAULTS, ...(sess.rt || {}), ...local };
}

async function saveRt(rt) {
  const { active, monitorPaused, autoResume, ...rest } = rt;
  await chrome.storage.local.set({ active, monitorPaused, autoResume });
  await chrome.storage.session.set({ rt: rest });
}

// 背景程式的動作排隊執行，避免同時改到狀態
let chain = Promise.resolve();
function serial(fn) {
  const p = chain.then(fn, fn);
  chain = p.catch(() => {});
  return p;
}

// ---------- 判斷狀態 ----------

function fmt(sec) {
  sec = Math.max(0, Math.round(sec));
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`;
}

async function evaluate(rt) {
  const s = await getSettings();
  const now = Date.now();
  const tabs = await chrome.tabs.query({ url: MELON_URLS });
  const alive = new Set(tabs.map((t) => String(t.id)));
  for (const id of Object.keys(rt.tabs)) if (!alive.has(id)) delete rt.tabs[id];

  let raw = "unknown";
  let detail = "沒有開著的 Melon 分頁";
  let song = null;

  if (tabs.length > 0) {
    let best = null; // 最「正常」的狀態
    const rank = { playing: 4, buffering: 3, paused: 2, ended: 2, error: 2 };
    for (const t of tabs) {
      const rec = rt.tabs[t.id] || { frames: {} };
      rec.url = t.url;
      rec.title = t.title;
      rt.tabs[t.id] = rec;
      if (t.audible) {
        best = "playing";
      }
      for (const f of Object.values(rec.frames)) {
        let st = f.state;
        // 「播放中」的回報要夠新才算（分頁在播時每 15 秒內一定會回報）
        if (st === "playing" && now - f.at > 45000 && !t.audible) st = "buffering";
        if (st && (!best || rank[st] > rank[best])) best = st;
        if ((st === "playing" || t.audible) && f.title) song = { title: f.title, artist: f.artist };
      }
    }
    if (best === "playing") {
      raw = "playing";
      detail = song ? `♪ ${song.title}${song.artist ? " — " + song.artist : ""}` : "正在播放";
    } else if (best === "buffering") {
      raw = "buffering";
      detail = "載入中或網路不穩";
    } else if (best) {
      raw = "stopped";
      detail = best === "error" ? "播放器發生錯誤" : best === "ended" ? "播放清單播完了" : "播放暫停或停止";
    } else {
      raw = "stopped";
      detail = "Melon 分頁沒有在播放";
    }
  }

  // 套用「暫停監控」與主開關
  let shown = raw;
  if (!rt.active) {
    shown = "idle";
  } else if (rt.monitorPaused) {
    if (rt.autoResume && raw === "playing") {
      rt.monitorPaused = false;
    } else {
      shown = "paused";
    }
  }

  rt.song = raw === "playing" ? song : rt.song;

  const bad = shown === "stopped" || shown === "buffering" || shown === "unknown";
  if (bad) {
    if (!rt.badSince) {
      rt.badSince = now;
      rt.alertsSent = 0;
      rt.acked = false;
    }
    rt.badState = shown;
    const elapsed = now - rt.badSince;
    const due =
      !rt.acked &&
      rt.alertsSent < s.maxAlerts &&
      elapsed >= s.thresholdSec * 1000 &&
      (rt.alertsSent === 0 || now - rt.lastAlertAt >= s.repeatMin * 60000);
    if (due) {
      rt.alertsSent++;
      rt.lastAlertAt = now;
      await alertStopped(s, rt, shown, detail, elapsed);
    }
    scheduleDue(s, rt, now);
  } else {
    if (shown === "playing" && rt.alertsSent > 0) {
      chrome.notifications.clear(ALERT_ID);
      if (s.notifyRecovery) {
        await notify(INFO_ID, "✅ Melon 恢復播放了", `停了 ${fmt((now - rt.badSince) / 1000)} 之後恢復播放。`, false);
        await sendRemote(s, "✅ Melon 恢復播放了", `停了 ${fmt((now - rt.badSince) / 1000)} 之後恢復播放。`, 3, ["white_check_mark"]);
      }
    }
    rt.badSince = 0;
    rt.badState = null;
    rt.alertsSent = 0;
    rt.lastAlertAt = 0;
    rt.acked = false;
    chrome.alarms.clear("due");
  }

  rt.state = shown;
  rt.detail = detail;
  rt.tabCount = tabs.length;
  rt.updatedAt = now;
  await updateBadge(rt);
  return rt;
}

function scheduleDue(s, rt, now) {
  if (rt.acked || rt.alertsSent >= s.maxAlerts) return;
  const next = rt.alertsSent === 0
    ? rt.badSince + s.thresholdSec * 1000
    : rt.lastAlertAt + s.repeatMin * 60000;
  chrome.alarms.create("due", { when: Math.max(next, now + 1000) });
}

async function updateBadge(rt) {
  const map = {
    playing: ["ON", "#4E9A5B"],
    buffering: ["…", "#8A8478"],
    stopped: ["!", "#C0502B"],
    unknown: ["?", "#C0502B"],
    paused: ["||", "#7B6BB0"],
    idle: ["", "#8A8478"]
  };
  const [text, color] = map[rt.state] || ["", "#8A8478"];
  await chrome.action.setBadgeText({ text });
  await chrome.action.setBadgeBackgroundColor({ color });
  await chrome.action.setTitle({ title: `刷瓜小幫手：${LABELS[rt.state] || ""}` });
}

// ---------- 通知 ----------

async function alertStopped(s, rt, state, detail, elapsed) {
  const title = `⚠️ Melon ${LABELS[state]}了`;
  const reason =
    state === "buffering" ? "一直在緩衝，可能是網路不穩。"
      : state === "unknown" ? "找不到正在播放的 Melon 分頁，可能被關掉了。"
        : `${detail}，請檢查一下。`;
  const body = `${reason} 已持續 ${fmt(elapsed / 1000)}。（第 ${rt.alertsSent}/${s.maxAlerts} 次提醒）`;
  await notify(ALERT_ID, title, body, true);
  await sendRemote(s, title, body, 5, ["warning"]);
  rt.lastAlert = { title, body, at: Date.now() };
}

function notify(id, title, message, important) {
  const opts = {
    type: "basic",
    iconUrl: "icons/icon128.png",
    title,
    message,
    priority: important ? 2 : 0,
    requireInteraction: important
  };
  if (important) opts.buttons = [{ title: "切到 Melon 分頁" }, { title: "知道了" }];
  return new Promise((resolve) => {
    try {
      chrome.notifications.clear(id, () => chrome.notifications.create(id, opts, () => resolve()));
    } catch (_) {
      resolve();
    }
  });
}

async function sendRemote(s, title, body, priority, tags, force = false) {
  if (!s.remoteEnabled && !force) return { ok: true, msg: "未開啟" };
  try {
    const res = await fetch(`${s.ntfyServer.replace(/\/+$/, "")}/`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        topic: s.topic,
        title,
        message: `【${s.deviceName}】${body}`,
        priority,
        tags
      })
    });
    return res.ok ? { ok: true, msg: "已送出" } : { ok: false, msg: `伺服器回應錯誤（${res.status}）` };
  } catch (e) {
    return { ok: false, msg: "送出失敗，請確認電腦有網路" };
  }
}

async function focusMelon() {
  const tabs = await chrome.tabs.query({ url: MELON_URLS });
  const t = tabs.find((x) => x.audible) || tabs[0];
  if (t) {
    await chrome.tabs.update(t.id, { active: true });
    await chrome.windows.update(t.windowId, { focused: true });
  } else {
    await chrome.tabs.create({ url: "https://www.melon.com/" });
  }
}

// ---------- 事件 ----------

function ensureAlarm() {
  chrome.alarms.get("tick", (a) => {
    if (!a) chrome.alarms.create("tick", { periodInMinutes: 0.5 });
  });
}

chrome.runtime.onInstalled.addListener(() => {
  ensureAlarm();
  serial(async () => saveRt(await evaluate(await getRt())));
});
chrome.runtime.onStartup.addListener(() => {
  ensureAlarm();
  serial(async () => saveRt(await evaluate(await getRt())));
});

chrome.alarms.onAlarm.addListener(() => {
  serial(async () => saveRt(await evaluate(await getRt())));
});

chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url) {
    // 換頁了：舊頁面的播放器回報作廢
    serial(async () => {
      const rt = await getRt();
      if (rt.tabs[tabId]) rt.tabs[tabId].frames = {};
      await saveRt(await evaluate(rt));
    });
  } else if ("audible" in info || info.status === "complete") {
    serial(async () => saveRt(await evaluate(await getRt())));
  }
});
chrome.tabs.onRemoved.addListener(() => {
  serial(async () => saveRt(await evaluate(await getRt())));
});

chrome.notifications.onButtonClicked.addListener((id, idx) => {
  if (id !== ALERT_ID) return;
  if (idx === 0) focusMelon();
  serial(async () => {
    const rt = await getRt();
    rt.acked = true;
    chrome.notifications.clear(ALERT_ID);
    chrome.alarms.clear("due");
    await saveRt(rt);
  });
});
chrome.notifications.onClicked.addListener((id) => {
  if (id === ALERT_ID || id === INFO_ID) focusMelon();
});

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.kind === "media" && sender.tab) {
    serial(async () => {
      const rt = await getRt();
      const id = String(sender.tab.id);
      const rec = rt.tabs[id] || { frames: {} };
      rec.frames[String(sender.frameId || 0)] = {
        state: msg.state,
        title: msg.title,
        artist: msg.artist,
        at: Date.now()
      };
      rt.tabs[id] = rec;
      await saveRt(await evaluate(rt));
    });
    return false;
  }

  // 彈出視窗的指令
  const handlers = {
    status: async () => {
      const rt = await evaluate(await getRt());
      await saveRt(rt);
      const s = await getSettings();
      return { rt, s };
    },
    setActive: async () => {
      const rt = await getRt();
      rt.active = !!msg.value;
      rt.monitorPaused = false;
      rt.badSince = 0;
      rt.alertsSent = 0;
      await saveRt(await evaluate(rt));
      return {};
    },
    setPaused: async () => {
      const rt = await getRt();
      rt.monitorPaused = !!msg.value;
      await saveRt(await evaluate(rt));
      return {};
    },
    setAutoResume: async () => {
      const rt = await getRt();
      rt.autoResume = !!msg.value;
      await saveRt(rt);
      return {};
    },
    saveSettings: async () => {
      await chrome.storage.local.set(msg.value || {});
      return {};
    },
    newTopic: async () => {
      const topic = newTopic();
      await chrome.storage.local.set({ topic });
      return { topic };
    },
    testLocal: async () => {
      await notify(INFO_ID, "🔔 刷瓜小幫手測試", "看得到這則就代表電腦通知正常。", false);
      return {};
    },
    testRemote: async () => {
      const s = await getSettings();
      return sendRemote(s, "🔔 刷瓜小幫手測試", "手機收得到這則，就代表設定成功。", 4, ["bell"], true);
    },
    focusMelon: async () => {
      await focusMelon();
      return {};
    }
  };
  const h = handlers[msg.cmd];
  if (!h) return false;
  serial(h).then(sendResponse, (e) => sendResponse({ error: String(e) }));
  return true; // 非同步回覆
});

ensureAlarm();
