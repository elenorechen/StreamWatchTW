// 把網頁裡回報的播放狀態轉交給擴充功能背景程式。
window.addEventListener("message", (e) => {
  if (e.source !== window) return;
  const d = e.data;
  if (!d || d.__swtw !== true) return;
  try {
    chrome.runtime.sendMessage({
      kind: "media",
      state: d.state,
      title: d.title,
      artist: d.artist,
      pageTitle: d.pageTitle,
      reason: d.reason
    }).catch(() => {});
  } catch (_) {
    // 擴充功能被重新載入時會出錯，忽略即可
  }
});
