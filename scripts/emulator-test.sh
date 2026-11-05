#!/usr/bin/env bash
# 在虛擬 Android 手機上自動測試刷瓜小幫手。
# 由 .github/workflows/emulator-test.yml 呼叫，結果寫到 test-results/。
set -u

OUT="${GITHUB_WORKSPACE:-$PWD}/test-results"
mkdir -p "$OUT"
REPORT="$OUT/report.md"
APP=tw.plli.streamwatch
PLAYER=tw.plli.fakeplayer
FAILS=0
SHOT=0

log()  { echo "$*" | tee -a "$REPORT"; }
pass() { log "- ✅ $*"; }
fail() { log "- ❌ $*"; FAILS=$((FAILS + 1)); }

shot() {
  SHOT=$((SHOT + 1))
  local name
  name=$(printf "%02d-%s.png" "$SHOT" "$1")
  adb exec-out screencap -p > "$OUT/$name"
  log "  - 截圖：\`$name\`"
}

app_front() { adb shell am start -n "$APP/.MainActivity" >/dev/null; sleep 3; }
player()    { adb shell am start -n "$PLAYER/.PlayerActivity" --es state "$1" >/dev/null; sleep 2; }

# 通知內容（含標題）
notif_dump() { adb shell dumpsys notification --noredact 2>/dev/null; }
has_notif()  { notif_dump | grep -F "$1" >/dev/null; }

# 在 timeout 秒內等到某則通知出現
wait_notif() {
  local text="$1" timeout="$2" i=0
  while [ $i -lt "$timeout" ]; do
    has_notif "$text" && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

check_alive() {
  if adb logcat -d -b crash 2>/dev/null | grep -q "$APP"; then
    fail "刷瓜小幫手發生閃退（$1）"
    adb logcat -d -b crash > "$OUT/crash-log.txt"
  else
    pass "沒有閃退（$1）"
  fi
}

echo "# 刷瓜小幫手 自動測試報告" > "$REPORT"
log ""
log "虛擬手機：Android $(adb shell getprop ro.build.version.release | tr -d '\r')（API $(adb shell getprop ro.build.version.sdk | tr -d '\r')）"
log ""

adb logcat -c || true
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true

# ---------- 安裝 ----------
log "## 1. 安裝"
if adb install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null; then pass "安裝刷瓜小幫手"; else fail "安裝刷瓜小幫手"; fi
if adb install -r fakeplayer/build/outputs/apk/debug/fakeplayer-debug.apk >/dev/null; then pass "安裝測試播放器"; else fail "安裝測試播放器"; fi
adb shell pm grant "$APP" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 預先設定：監控測試播放器、停 15 秒就通知、間隔 1 分鐘
PREFS=$(mktemp)
cat > "$PREFS" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="active" value="true" />
    <set name="monitored">
        <string>$PLAYER</string>
    </set>
    <int name="threshold" value="15" />
    <int name="repeatMin" value="1" />
    <int name="maxAlerts" value="2" />
    <boolean name="remote" value="false" />
</map>
EOF
adb shell "run-as $APP mkdir -p /data/data/$APP/shared_prefs"
adb shell "run-as $APP sh -c 'cat > /data/data/$APP/shared_prefs/streamwatch.xml'" < "$PREFS"
if adb shell "run-as $APP cat /data/data/$APP/shared_prefs/streamwatch.xml" | grep -q "$PLAYER"; then
  pass "寫入測試設定"
else
  fail "寫入測試設定"
fi

# ---------- 尚未開啟通知存取權 ----------
log ""
log "## 2. 還沒開通知存取權時的畫面"
player playing
app_front
shot "setup-needed"
if adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | grep -q "$APP"; then
  pass "App 可以正常開啟"
else
  fail "App 沒有在前景"
fi
check_alive "開啟 App"

# ---------- 開啟權限 ----------
log ""
log "## 3. 開啟通知存取權、電池不最佳化"
adb shell cmd notification allow_listener "$APP/$APP.WatchListenerService" || true
adb shell dumpsys deviceidle whitelist +"$APP" >/dev/null || true
sleep 8
if adb shell dumpsys notification | grep -q "WatchListenerService"; then
  pass "監控服務已連上"
else
  fail "監控服務沒有連上"
fi

# ---------- 播放中 ----------
log ""
log "## 4. 播放中"
player playing
app_front
sleep 4
shot "playing"
if has_notif "刷瓜小幫手監控中"; then pass "常駐「監控中」通知有出現"; else fail "沒有看到常駐「監控中」通知"; fi
check_alive "播放中"

# ---------- 停止 → 通知 ----------
log ""
log "## 5. 暫停播放 → 15 秒後通知"
player paused
app_front
sleep 6
shot "stopped-countdown"
if wait_notif "測試播放器 停止了" 40; then
  pass "停止超過 15 秒後跳出提醒通知"
else
  fail "停止後 40 秒內沒有收到提醒通知"
fi
adb shell cmd statusbar expand-notifications || true
sleep 2
shot "alert-notification"
adb shell cmd statusbar collapse || true
app_front
shot "stopped-alerted"
check_alive "停止提醒"

# ---------- 恢復播放 ----------
log ""
log "## 6. 恢復播放 → 通知恢復"
player playing
if wait_notif "恢復播放了" 20; then
  pass "恢復播放後收到「已恢復」通知"
else
  fail "恢復播放後沒有收到「已恢復」通知"
fi
app_front
shot "recovered"

# ---------- 緩衝太久 ----------
log ""
log "## 7. 一直緩衝 → 通知"
player buffering
if wait_notif "測試播放器 緩衝中了" 40; then
  pass "緩衝超過 15 秒後跳出提醒"
else
  fail "緩衝 40 秒內沒有收到提醒"
fi
player playing
sleep 6

# ---------- App 被關掉 ----------
log ""
log "## 8. 音樂 App 被關掉 → 無法確認 → 通知"
adb shell am force-stop "$PLAYER"
sleep 4
app_front
shot "app-killed"
if wait_notif "測試播放器 無法確認了" 40; then
  pass "音樂 App 被關掉後跳出提醒"
else
  fail "音樂 App 被關掉 40 秒內沒有收到提醒"
fi
check_alive "音樂 App 被關掉"

# ---------- 刷歌紀錄小卡 ----------
log ""
log "## 8-1. 刷歌紀錄小卡"
player playing
sleep 45
STATS=$(adb shell "run-as $APP cat /data/data/$APP/shared_prefs/stats.xml" 2>/dev/null | tr -d '\r')
echo "$STATS" > "$OUT/stats.xml"
INT=$(echo "$STATS" | grep -oE '&quot;i&quot;:[0-9]+' | grep -oE '[0-9]+$' | head -1)
PLAY=$(echo "$STATS" | grep -oE '&quot;p&quot;:[0-9]+' | grep -oE '[0-9]+$' | head -1)
LONG=$(echo "$STATS" | grep -oE '&quot;l&quot;:[0-9]+' | grep -oE '[0-9]+$' | head -1)
log "  - 紀錄：播放 ${PLAY:-?} 秒、最長連續 ${LONG:-?} 秒、中斷 ${INT:-?} 次"
if [ "${PLAY:-0}" -ge 35 ]; then pass "有記錄到播放時數"; else fail "播放時數太少"; fi
if [ "${LONG:-0}" -ge 35 ]; then pass "最長連續播放正確"; else fail "最長連續播放太短"; fi
if [ "${INT:-0}" -ge 3 ]; then pass "記錄到 ${INT} 次中斷"; else fail "中斷次數不對（${INT:-0}）"; fi
adb shell am start -n "$APP/.MainActivity" --ez show_card true >/dev/null
sleep 4
shot "stats-card"
check_alive "產生小卡"
adb shell input keyevent KEYCODE_BACK
sleep 1

# ---------- 設定頁截圖 ----------
log ""
log "## 9. 介面截圖（往下捲）"
app_front
for n in 1 2 3 4; do
  adb shell input swipe 540 1700 540 500 400
  sleep 1
  shot "scroll-$n"
done
check_alive "捲動畫面"

# ---------- 停止監控 ----------
log ""
log "## 10. 停止監控"
adb shell am force-stop "$APP"
log "（測試結束）"

log ""
if [ "$FAILS" -eq 0 ]; then
  log "**結果：全部通過**"
else
  log "**結果：有 $FAILS 項沒通過**"
fi

notif_dump > "$OUT/notifications-dump.txt" 2>&1 || true
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true

[ "$FAILS" -eq 0 ]
