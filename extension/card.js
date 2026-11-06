// 畫出刷歌紀錄小卡（Y2K 風格，跟手機版同一個設計，1080 × 1350）
const W = 1080;
const H = 1350;
const C = {
  ink: "#3B2F63",        // 深紫：外框、主要文字
  sub: "#7A6CA8",        // 淺紫：小標
  white: "#FFFDFE",
  pink: "#FF6FB1",       // 大數字
  bgTop: "#E6DDFF", bgMid: "#FFE1F0", bgBot: "#D9EEFF",
  barL: "#FFB8DA", barR: "#B9DCFF",
  mint: "#D5F5E3", lemon: "#FFF2B3", rose: "#FFDCEB", lilac: "#E5DCFF",
  marker: "#FFF09A"
};
const MEMBER_COLORS = ["#4F8FE8", "#9672D6", "#F08DB5", "#E5484D", "#2E2B26"]; // 💙💜💗❤️🖤
const FONT = '"Microsoft JhengHei", "PingFang TC", "Noto Sans TC", "Noto Sans CJK TC", sans-serif';

// 固定位置的閃亮星星 [x, y, 大小, 顏色]
const SPARKLES = [
  [70, 330, 22, "#FFFFFF"], [1015, 300, 30, "#FFFFFF"], [985, 640, 18, "#FFD1E8"],
  [45, 760, 26, "#FFFFFF"], [1040, 990, 24, "#FFFFFF"], [60, 1180, 18, "#C9B8FF"],
  [960, 1290, 28, "#FFFFFF"], [150, 1300, 16, "#FFFFFF"], [540, 1312, 14, "#FFC4E1"]
];

function font(size, bold) {
  return `${bold ? "900" : "500"} ${size}px ${FONT}`;
}

function rr(ctx, l, t, r, b, radius) {
  ctx.beginPath();
  ctx.roundRect(l, t, r - l, b - t, radius);
}

/** 貼紙風格的方塊：硬陰影＋粗外框 */
function sticker(ctx, l, t, r, b, radius, fill, shadow = 10, line = 5) {
  if (shadow) {
    rr(ctx, l + shadow, t + shadow, r + shadow, b + shadow, radius);
    ctx.fillStyle = C.ink;
    ctx.fill();
  }
  rr(ctx, l, t, r, b, radius);
  ctx.fillStyle = fill;
  ctx.fill();
  ctx.lineWidth = line;
  ctx.strokeStyle = C.ink;
  ctx.stroke();
}

/** 四角星 ✦ */
function sparkle(ctx, x, y, r, color) {
  const k = r * 0.28;
  ctx.beginPath();
  ctx.moveTo(x, y - r);
  ctx.quadraticCurveTo(x + k, y - k, x + r, y);
  ctx.quadraticCurveTo(x + k, y + k, x, y + r);
  ctx.quadraticCurveTo(x - k, y + k, x - r, y);
  ctx.quadraticCurveTo(x - k, y - k, x, y - r);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
}

/** 愛心 ♥ （x, y 是中心） */
function heart(ctx, x, y, s, color) {
  ctx.beginPath();
  ctx.moveTo(x, y + s * 0.45);
  ctx.bezierCurveTo(x - s * 0.95, y - s * 0.15, x - s * 0.5, y - s * 0.85, x, y - s * 0.35);
  ctx.bezierCurveTo(x + s * 0.5, y - s * 0.85, x + s * 0.95, y - s * 0.15, x, y + s * 0.45);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
  ctx.lineWidth = 3;
  ctx.strokeStyle = C.ink;
  ctx.stroke();
}

/** 外框字（泡泡字） */
function bubbleText(ctx, text, x, y, size, fill, stroke = C.ink, line = 10) {
  ctx.font = font(size, true);
  ctx.lineJoin = "round";
  ctx.lineWidth = line;
  ctx.strokeStyle = stroke;
  ctx.strokeText(text, x, y);
  ctx.fillStyle = fill;
  ctx.fillText(text, x, y);
  return ctx.measureText(text).width;
}

function fitFont(ctx, text, maxW, size, minSize, bold = true) {
  ctx.font = font(size, bold);
  while (ctx.measureText(text).width > maxW && size > minSize) {
    size -= 2;
    ctx.font = font(size, bold);
  }
  return size;
}

function dateLabel(key) {
  const [y, m, d] = key.split("-").map(Number);
  const wk = "日一二三四五六"[new Date(y, m - 1, d).getDay()];
  return `${y}.${String(m).padStart(2, "0")}.${String(d).padStart(2, "0")}（${wk}）`;
}

function drawCard(ctx, s) {
  ctx.textBaseline = "alphabetic";

  // ---- 背景：粉彩漸層＋星星 ----
  const g = ctx.createLinearGradient(0, 0, W, H);
  g.addColorStop(0, C.bgTop);
  g.addColorStop(0.5, C.bgMid);
  g.addColorStop(1, C.bgBot);
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, W, H);
  // 背景小圓點
  ctx.fillStyle = "rgba(255,255,255,0.55)";
  for (let yy = 40; yy < H; yy += 70) {
    for (let xx = (yy / 70) % 2 ? 40 : 75; xx < W; xx += 70) {
      ctx.beginPath();
      ctx.arc(xx, yy, 3, 0, Math.PI * 2);
      ctx.fill();
    }
  }
  for (const [x, y, r, col] of SPARKLES) sparkle(ctx, x, y, r, col);

  // ---- 頂部貼紙：名稱＋愛心 ----
  ctx.font = font(34, true);
  const tag = "刷瓜小幫手 ☆ 刷歌紀錄";
  const tagW = ctx.measureText(tag).width + 60;
  sticker(ctx, 60, 58, 60 + tagW, 128, 35, C.white, 6, 4);
  ctx.fillStyle = C.ink;
  ctx.fillText(tag, 90, 105);
  let hx = W - 60 - 4 * 52 - 22;
  for (const col of MEMBER_COLORS) {
    heart(ctx, hx, 96, 40, col);
    hx += 52;
  }

  // ---- 視窗 ----
  const wl = 60, wt = 165, wr = W - 72, wb = 1215;
  sticker(ctx, wl, wt, wr, wb, 34, C.white, 14, 6);
  // 標題列
  ctx.save();
  rr(ctx, wl, wt, wr, wt + 82, [34, 34, 0, 0]);
  const bar = ctx.createLinearGradient(wl, 0, wr, 0);
  bar.addColorStop(0, C.barL);
  bar.addColorStop(1, C.barR);
  ctx.fillStyle = bar;
  ctx.fill();
  ctx.restore();
  ctx.beginPath();
  ctx.moveTo(wl, wt + 82);
  ctx.lineTo(wr, wt + 82);
  ctx.lineWidth = 6;
  ctx.strokeStyle = C.ink;
  ctx.stroke();
  rr(ctx, wl, wt, wr, wb, 34);
  ctx.stroke();
  // 三個按鈕
  [["#FF8FC2", 0], ["#FFE27A", 1], ["#8EE3B5", 2]].forEach(([col, i]) => {
    ctx.beginPath();
    ctx.arc(wl + 48 + i * 44, wt + 41, 14, 0, Math.PI * 2);
    ctx.fillStyle = col;
    ctx.fill();
    ctx.lineWidth = 4;
    ctx.stroke();
  });
  ctx.fillStyle = C.ink;
  ctx.font = font(32, true);
  const dl = dateLabel(s.date);
  ctx.fillText(dl, wr - 40 - ctx.measureText(dl).width, wt + 53);

  const ix = wl + 50;           // 視窗內左邊界
  const iw = wr - wl - 100;     // 視窗內寬度

  // ---- 標語（螢光筆底線） ----
  const mood = statsMood(s);
  const moodSize = fitFont(ctx, mood, iw, 64, 36);
  const moodW = ctx.measureText(mood).width;
  ctx.fillStyle = C.marker;
  rr(ctx, ix - 8, 338 - moodSize * 0.42, ix + moodW + 8, 338 + 12, 10);
  ctx.fill();
  ctx.fillStyle = C.ink;
  ctx.font = font(moodSize, true);
  ctx.fillText(mood, ix, 338);

  // ---- 今日播放時數 ----
  ctx.font = font(32, true);
  const lab = "♪ 今日播放時數";
  const labW = ctx.measureText(lab).width + 44;
  sticker(ctx, ix, 392, ix + labW, 446, 27, C.lilac, 0, 4);
  ctx.fillStyle = C.ink;
  ctx.fillText(lab, ix + 22, 431);
  const total = Math.round(s.playSec);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  let x = ix;
  const baseY = 610;
  const seg = (num, unit) => {
    x += bubbleText(ctx, num, x, baseY, 160, C.pink, C.ink, 12) + 14;
    ctx.font = font(54, true);
    ctx.fillStyle = C.ink;
    ctx.fillText(unit, x, baseY);
    x += ctx.measureText(unit).width + 30;
  };
  if (h > 0) seg(String(h), "小時");
  seg(String(m), "分");

  // ---- 四張貼紙 ----
  const gap = 34;
  const cellW = (iw - gap) / 2;
  const cellH = 178;
  const top = 662;
  const cells = [
    ["最長連續播放", fmtHm(s.longestSec), C.mint, "♪"],
    ["中斷次數", `${s.interrupts} 次`, C.lemon, "☆"],
    ["平均救回時間", s.recovered > 0 ? fmtShort(s.avgRecoverSec) : "—", C.rose, "♡"],
    ["連續刷歌天數", `${s.streakDays} 天`, C.lilac, "★"]
  ];
  cells.forEach(([label, value, fill, icon], i) => {
    const l = ix + (i % 2) * (cellW + gap);
    const t = top + Math.floor(i / 2) * (cellH + gap);
    sticker(ctx, l, t, l + cellW, t + cellH, 26, fill, 8, 5);
    ctx.fillStyle = C.sub;
    ctx.font = font(30, true);
    ctx.fillText(label, l + 32, t + 62);
    ctx.fillStyle = C.ink;
    ctx.font = font(40, true);
    ctx.fillText(icon, l + cellW - 66, t + 64);
    const vs = fitFont(ctx, value, cellW - 64, 58, 28);
    ctx.font = font(vs, true);
    ctx.fillText(value, l + 32, t + 140);
  });

  // ---- 近 7 天累計 ----
  const wkTop = top + 2 * cellH + gap + 34;
  ctx.save();
  rr(ctx, ix, wkTop, ix + iw, wkTop + 84, 42);
  const wg = ctx.createLinearGradient(ix, 0, ix + iw, 0);
  wg.addColorStop(0, C.barR);
  wg.addColorStop(1, C.barL);
  ctx.fillStyle = wg;
  ctx.fill();
  ctx.lineWidth = 5;
  ctx.strokeStyle = C.ink;
  ctx.stroke();
  ctx.restore();
  ctx.fillStyle = C.ink;
  const wkText = `近 7 天累計 ☆ ${fmtHm(s.weekPlaySec)}`;
  fitFont(ctx, wkText, iw - 80, 38, 26);
  ctx.fillText(wkText, ix + 40, wkTop + 56);

  // ---- 頁尾 ----
  ctx.fillStyle = C.ink;
  ctx.font = font(30, true);
  const foot = "刷瓜小幫手 ・ 刷歌停了會通知你 ♡";
  ctx.fillText(foot, (W - ctx.measureText(foot).width) / 2, 1290);
}

const canvas = document.getElementById("card");
const msg = (t) => {
  document.getElementById("msg").textContent = t;
};

async function load() {
  let s;
  try {
    s = await chrome.runtime.sendMessage({ cmd: "stats" });
  } catch (_) {}
  if (!s || !s.date) s = statsSummary({});
  await document.fonts.ready;
  drawCard(canvas.getContext("2d"), s);
  return s;
}

const ready = load();

document.getElementById("download").addEventListener("click", async () => {
  const s = await ready;
  const a = document.createElement("a");
  a.download = `刷歌紀錄_${s.date}.png`;
  a.href = canvas.toDataURL("image/png");
  a.click();
  msg("已下載");
});

document.getElementById("copy").addEventListener("click", async () => {
  await ready;
  try {
    const blob = await new Promise((r) => canvas.toBlob(r, "image/png"));
    await navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
    msg("已複製，可以直接貼上");
  } catch (_) {
    msg("這個瀏覽器不支援複製圖片，請改用下載");
  }
});
