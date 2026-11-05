// 畫出刷歌紀錄小卡（跟手機版同一個設計，1080 × 1350）
const W = 1080;
const H = 1350;
const C = {
  bg: "#F7F3EA", card: "#FFFDF8", border: "#ECE6DA", text: "#2E2B26", sub: "#7A746A",
  green: "#4E9A5B", greenBg: "#DCEBDD", purple: "#4B3F72"
};
const MEMBER_COLORS = ["#4F8FE8", "#9672D6", "#F08DB5", "#E5484D", "#2E2B26"]; // 💙💜💗❤️🖤
const FONT = '"Microsoft JhengHei", "PingFang TC", "Noto Sans TC", "Noto Sans CJK TC", sans-serif';

function font(size, bold) {
  return `${bold ? "700" : "400"} ${size}px ${FONT}`;
}

function roundRect(ctx, l, t, r, b, radius, fill, stroke = true) {
  ctx.beginPath();
  ctx.roundRect(l, t, r - l, b - t, radius);
  ctx.fillStyle = fill;
  ctx.fill();
  if (stroke) {
    ctx.lineWidth = 3;
    ctx.strokeStyle = C.border;
    ctx.stroke();
  }
}

function dateLabel(key) {
  const [y, m, d] = key.split("-").map(Number);
  const wk = "日一二三四五六"[new Date(y, m - 1, d).getDay()];
  return `${y} / ${String(m).padStart(2, "0")} / ${String(d).padStart(2, "0")}（${wk}）`;
}

function drawCard(ctx, s) {
  ctx.fillStyle = C.bg;
  ctx.fillRect(0, 0, W, H);
  ctx.textBaseline = "alphabetic";
  const pad = 80;

  // 標題
  ctx.fillStyle = C.sub;
  ctx.font = font(34, true);
  ctx.fillText("刷瓜小幫手 · 刷歌紀錄", pad, 140);
  ctx.font = font(34, false);
  ctx.fillText(dateLabel(s.date), pad, 192);
  let dotX = W - pad - 5 * 30 + 13;
  for (const col of MEMBER_COLORS) {
    ctx.beginPath();
    ctx.arc(dotX, 128, 11, 0, Math.PI * 2);
    ctx.fillStyle = col;
    ctx.fill();
    dotX += 30;
  }
  ctx.fillStyle = C.text;
  ctx.font = font(62, true);
  ctx.fillText(statsMood(s), pad, 280);

  // 今日播放時數
  const bigTop = 330;
  const bigBottom = 610;
  roundRect(ctx, pad, bigTop, W - pad, bigBottom, 40, C.card);
  ctx.fillStyle = C.sub;
  ctx.font = font(38, true);
  ctx.fillText("今日播放時數", pad + 56, bigTop + 82);
  const total = Math.round(s.playSec);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  let x = pad + 50;
  const baseY = bigTop + 235;
  const seg = (num, unit) => {
    ctx.font = font(150, true);
    ctx.fillStyle = C.green;
    ctx.fillText(num, x, baseY);
    x += ctx.measureText(num).width + 12;
    ctx.font = font(52, true);
    ctx.fillStyle = C.text;
    ctx.fillText(unit, x, baseY);
    x += ctx.measureText(unit).width + 34;
  };
  if (h > 0) seg(String(h), "小時");
  seg(String(m), "分");

  // 四個小格
  const gap = 28;
  const cellW = (W - pad * 2 - gap) / 2;
  const cellH = 190;
  const gridTop = bigBottom + gap;
  const cells = [
    ["最長連續播放", fmtHm(s.longestSec), C.green],
    ["中斷次數", `${s.interrupts} 次`, s.interrupts === 0 ? C.green : C.text],
    ["平均救回時間", s.recovered > 0 ? fmtShort(s.avgRecoverSec) : "—", C.text],
    ["連續刷歌天數", `${s.streakDays} 天`, C.purple]
  ];
  cells.forEach(([label, value, color], i) => {
    const l = pad + (i % 2) * (cellW + gap);
    const t = gridTop + Math.floor(i / 2) * (cellH + gap);
    roundRect(ctx, l, t, l + cellW, t + cellH, 40, C.card);
    ctx.fillStyle = C.sub;
    ctx.font = font(34, true);
    ctx.fillText(label, l + 44, t + 70);
    let size = 60;
    ctx.font = font(size, true);
    while (ctx.measureText(value).width > cellW - 88 && size > 30) {
      size -= 2;
      ctx.font = font(size, true);
    }
    ctx.fillStyle = color;
    ctx.fillText(value, l + 44, t + 150);
  });

  // 近 7 天累計
  const weekTop = gridTop + 2 * cellH + gap * 2;
  roundRect(ctx, pad, weekTop, W - pad, weekTop + 100, 32, C.greenBg, false);
  ctx.fillStyle = C.green;
  ctx.font = font(40, true);
  ctx.fillText(`近 7 天累計  ${fmtHm(s.weekPlaySec)}`, pad + 44, weekTop + 64);

  // 頁尾
  ctx.fillStyle = C.sub;
  ctx.font = font(30, false);
  const foot = "刷瓜小幫手 · 刷歌停了會通知你的小工具";
  ctx.fillText(foot, (W - ctx.measureText(foot).width) / 2, H - 80);
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
