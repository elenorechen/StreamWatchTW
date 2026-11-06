// 刷歌紀錄：每日播放時數、最長連續播放、中斷次數、平均救回時間、連續天數。
// 只存在這台電腦的瀏覽器裡，只有時數和次數，不記錄歌曲或帳號。
// 背景程式（importScripts）和小卡頁面都會載入這個檔案。

const STATS_STREAK_MIN_SEC = 3600; // 一天播放滿 1 小時才算「有刷歌的一天」
const STATS_KEEP_DAYS = 60;
const STATS_MAX_GAP_MS = 75000; // 背景每 30 秒檢查一次；間隔太久（電腦休眠）就不算

function statsDayKey(ms = Date.now()) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function statsEmptyDay() {
  return { p: 0, l: 0, i: 0, r: 0, d: 0 }; // 播放秒數、最長連續、中斷、救回次數、停止總秒數
}

function fmtHm(sec) {
  sec = Math.max(0, Math.round(sec));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h > 0) return `${h} 小時 ${m} 分`;
  if (m > 0) return `${m} 分鐘`;
  return `${sec} 秒`;
}

function fmtShort(sec) {
  sec = Math.max(0, Math.round(sec));
  if (sec < 60) return `${sec} 秒`;
  if (sec < 3600) return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`;
  return fmtHm(sec);
}

/**
 * 背景程式每次判斷完狀態就呼叫一次。
 * st 是存在 session 的執行中狀態（會被修改）。
 * counting：監控中而且沒有暫停；playing：Melon 正在播放。
 */
async function statsUpdate(st, now, counting, playing, thresholdSec) {
  const { stats } = await chrome.storage.local.get({ stats: { days: {} } });
  const days = stats.days || {};
  const key = statsDayKey(now);
  const t = days[key] || statsEmptyDay();

  const dt = st.sLastAt ? now - st.sLastAt : 0;
  const validGap = dt > 0 && dt <= STATS_MAX_GAP_MS;
  st.sLastAt = now;

  if (!counting) {
    st.sStretch = 0;
    st.sBadStart = 0;
    st.sBadCounted = false;
    st.sLastPlaying = false;
    return;
  }

  if (playing) {
    if (validGap && st.sLastPlaying) {
      t.p += dt / 1000;
      st.sStretch = (st.sStretch || 0) + dt / 1000;
    }
    if ((st.sStretch || 0) > t.l) t.l = st.sStretch;
    if (st.sBadStart) {
      if (st.sBadCounted) {
        t.r += 1;
        t.d += (now - st.sBadStart) / 1000;
      }
      st.sBadStart = 0;
      st.sBadCounted = false;
    }
  } else {
    // 換歌時的短暫緩衝不算中斷，也不打斷「連續播放」；停到通知門檻才算一次中斷
    if (!st.sBadStart) st.sBadStart = now;
    if (!st.sBadCounted && now - st.sBadStart >= thresholdSec * 1000) {
      st.sBadCounted = true;
      st.sStretch = 0;
      t.i += 1;
    }
  }
  st.sLastPlaying = playing;

  days[key] = t;
  const keys = Object.keys(days).sort();
  while (keys.length > STATS_KEEP_DAYS) delete days[keys.shift()];
  await chrome.storage.local.set({ stats: { days } });
}

function statsSummary(days, stretchSec = 0, nowMs = Date.now()) {
  const todayKey = statsDayKey(nowMs);
  const today = { ...statsEmptyDay(), ...(days[todayKey] || {}) };
  let week = 0;
  for (let k = 0; k < 7; k++) {
    const d = days[statsDayKey(nowMs - k * 86400000)];
    if (d) week += d.p;
  }
  let streak = 0;
  let k = (today.p >= STATS_STREAK_MIN_SEC) ? 0 : 1;
  while (true) {
    const d = days[statsDayKey(nowMs - k * 86400000)];
    if (!d || d.p < STATS_STREAK_MIN_SEC) break;
    streak++;
    k++;
  }
  return {
    date: todayKey,
    playSec: today.p,
    longestSec: today.l,
    interrupts: today.i,
    recovered: today.r,
    avgRecoverSec: today.r > 0 ? today.d / today.r : 0,
    weekPlaySec: week,
    streakDays: streak,
    stretchSec
  };
}

function statsMood(s) {
  if (s.playSec < 1) return "今天還沒開刷唷 ☆彡";
  if (s.playSec >= 12 * 3600 && s.interrupts === 0) return "整天零中斷！超神der ☆彡";
  if (s.interrupts === 0) return "零中斷達成 ♡ 讚啦～";
  if (s.recovered > 0 && s.avgRecoverSec <= 300) return "斷線秒救回 ♡ 超強der";
  return "今日刷歌任務完成 ☆彡";
}
