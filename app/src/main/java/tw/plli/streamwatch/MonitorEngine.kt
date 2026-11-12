package tw.plli.streamwatch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.SystemClock

/** 每個 App 卡片上顯示的狀態 */
enum class WatchState(val label: String) {
    PLAYING("播放中"),
    BUFFERING("緩衝中"),
    STOPPED("停止"),
    UNKNOWN("無法確認"),
    PAUSED("暫停監控"),
    IDLE("等待開始");

    val isBad get() = this == BUFFERING || this == STOPPED || this == UNKNOWN
}

class AppStatus(val pkg: String) {
    var state: WatchState = WatchState.IDLE
    var songTitle: String? = null
    var artist: String? = null
    /** 實際在播放的 App（例如 Stationhead 其實是 Spotify 在播）；跟自己一樣時為 null */
    var via: String? = null
    /** 開始不正常的時間（elapsedRealtime），0 表示目前正常 */
    var badSince: Long = 0
    var alertsSent: Int = 0
    var lastAlertAt: Long = 0
    var acked: Boolean = false
}

/**
 * 監控核心：每隔幾秒由 WatchListenerService 呼叫 tick()，
 * 判斷每個 App 的播放狀態，時間到了就發通知。
 * 全部在主執行緒上執行，所以不需要另外加鎖。
 */
object MonitorEngine {
    val statuses = LinkedHashMap<String, AppStatus>()

    /** 通知存取權是否已連上（MainActivity 用來顯示提醒） */
    @Volatile var listenerConnected = false
    @Volatile var lastTickAt = 0L

    private var batteryAlerted = false
    private var wasCharging: Boolean? = null

    fun statusFor(pkg: String): AppStatus = statuses.getOrPut(pkg) { AppStatus(pkg) }

    fun tick(ctx: Context, controllers: List<MediaController>?) {
        val prefs = Prefs(ctx)
        val now = SystemClock.elapsedRealtime()
        lastTickAt = now
        val monitored = prefs.monitoredPackages

        statuses.keys.retainAll(monitored)

        for (pkg in monitored) {
            val st = statusFor(pkg)
            val raw = rawState(pkg, controllers, st)

            val shown: WatchState = when {
                !prefs.monitoringActive -> WatchState.IDLE
                prefs.isPaused(pkg) -> {
                    if (prefs.isAutoResume(pkg) && raw == WatchState.PLAYING) {
                        prefs.setPaused(pkg, false)
                        WatchState.PLAYING
                    } else WatchState.PAUSED
                }
                else -> raw
            }
            st.state = shown

            when {
                shown.isBad -> handleBad(ctx, prefs, st, now)
                shown == WatchState.PLAYING -> {
                    if (st.alertsSent > 0) {
                        Alerts.cancel(ctx, Alerts.notificationIdFor(pkg))
                        Alerts.stopAlarm()
                        if (prefs.notifyRecovery) {
                            val name = appLabel(ctx, pkg)
                            Alerts.infoNotify(
                                ctx, prefs, Alerts.notificationIdFor(pkg),
                                "✅ $name 恢復播放了",
                                "停了 ${formatDuration((now - st.badSince) / 1000)} 之後恢復播放。",
                                important = false
                            )
                        }
                    }
                    reset(st)
                }
                else -> reset(st) // PAUSED / IDLE
            }
        }

        // 刷歌紀錄：任何一個 App 在播就算播放中；全部暫停監控時不記錄
        val shownStates = monitored.mapNotNull { statuses[it]?.state }
        val anyPlaying = shownStates.any { it == WatchState.PLAYING }
        val counting = prefs.monitoringActive && shownStates.any { it == WatchState.PLAYING || it.isBad }
        try {
            Stats.update(ctx, now, counting, anyPlaying, prefs.thresholdSec)
        } catch (_: Exception) {
        }

        if (prefs.monitoringActive) checkBattery(ctx, prefs) else {
            batteryAlerted = false
            wasCharging = null
        }
    }

    private fun reset(st: AppStatus) {
        st.badSince = 0
        st.alertsSent = 0
        st.lastAlertAt = 0
        st.acked = false
    }

    private fun handleBad(ctx: Context, prefs: Prefs, st: AppStatus, now: Long) {
        if (st.badSince == 0L) {
            st.badSince = now
            st.alertsSent = 0
            st.acked = false
        }
        if (st.acked) return
        // 同一個播放器已經由另一個監控中的 App 負責通知時，不重複通知
        // （例如同時勾了 Stationhead 和 Spotify，Spotify 停了只要通知一次）
        if (coveredByAnother(prefs, st.pkg)) return
        val elapsed = now - st.badSince
        val threshold = prefs.thresholdSec * 1000L
        val repeat = prefs.repeatMin * 60_000L
        val max = prefs.maxAlerts

        val due = (max == Prefs.UNLIMITED || st.alertsSent < max) && elapsed >= threshold &&
            (st.alertsSent == 0 || now - st.lastAlertAt >= repeat)
        if (!due) return

        st.alertsSent++
        st.lastAlertAt = now
        val name = appLabel(ctx, st.pkg)
        val title = "⚠️ $name ${st.state.label}了"
        val reason = when (st.state) {
            WatchState.BUFFERING -> "一直在緩衝，可能是網路不穩。"
            WatchState.UNKNOWN -> "讀不到播放狀態，App 可能被關掉了。"
            else -> "播放停止了，請檢查一下。"
        }
        val count = if (max == Prefs.UNLIMITED) "第 ${st.alertsSent} 次提醒" else "第 ${st.alertsSent}/$max 次提醒"
        val body = "$reason 已持續 ${formatDuration(elapsed / 1000)}。（$count）"
        Alerts.alertStopped(ctx, prefs, st.pkg, name, title, body)
    }

    private fun coveredByAnother(prefs: Prefs, pkg: String): Boolean =
        prefs.monitoredPackages.any { other ->
            other != pkg && !prefs.isPaused(other) && pkg in (KnownApps.playsVia[other] ?: emptyList())
        }

    /** 使用者按了「知道了」：這次停止不再重複提醒，恢復播放後重新計算 */
    fun ack(ctx: Context, pkg: String) {
        statuses[pkg]?.acked = true
        Alerts.cancel(ctx, Alerts.notificationIdFor(pkg))
        Alerts.stopAlarm()
    }

    private fun rawState(pkg: String, controllers: List<MediaController>?, st: AppStatus): WatchState {
        if (controllers == null) return WatchState.UNKNOWN
        val sources = KnownApps.sourcesFor(pkg)
        val mine = controllers.filter { it.packageName in sources }
        if (mine.isEmpty()) {
            st.songTitle = null
            st.artist = null
            st.via = null
            return WatchState.UNKNOWN
        }
        var best: MediaController? = null
        var result = WatchState.STOPPED
        for (c in mine) {
            val s = mapPlayback(c.playbackState)
            if (best == null || rank(s) > rank(result)) {
                result = s
                best = c
            }
        }
        st.via = best?.packageName?.takeIf { it != pkg }
        val md = best?.metadata
        st.songTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        st.artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        return result
    }

    private fun rank(s: WatchState) = when (s) {
        WatchState.PLAYING -> 3
        WatchState.BUFFERING -> 2
        WatchState.STOPPED -> 1
        else -> 0
    }

    private fun mapPlayback(ps: PlaybackState?): WatchState {
        return when (ps?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> WatchState.PLAYING
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> WatchState.BUFFERING
            else -> WatchState.STOPPED // 暫停、停止、錯誤、沒有狀態
        }
    }

    private fun checkBattery(ctx: Context, prefs: Prefs) {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale

        // 充電線鬆脫
        val prev = wasCharging
        if (prefs.chargeAlert && prev == true && !plugged) {
            Alerts.infoNotify(
                ctx, prefs, Alerts.ID_CHARGE,
                "🔌 刷歌手機沒在充電了",
                "充電線可能鬆脫了，目前電量 $pct%。",
                important = true
            )
        }
        if (plugged) Alerts.cancel(ctx, Alerts.ID_CHARGE)
        wasCharging = plugged

        // 電量過低
        if (prefs.batteryAlert && !plugged && pct <= prefs.batteryThreshold && !batteryAlerted) {
            batteryAlerted = true
            Alerts.infoNotify(
                ctx, prefs, Alerts.ID_BATTERY,
                "🪫 刷歌手機電量剩 $pct%",
                "記得接上充電器，沒電就會停止播放。",
                important = true
            )
        }
        if (plugged || pct > prefs.batteryThreshold + 5) batteryAlerted = false
    }

    private val labelCache = HashMap<String, String>()

    fun appLabel(ctx: Context, pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            KnownApps.names[pkg] ?: pkg
        }
        labelCache[pkg] = label
        return label
    }

    fun formatDuration(sec: Long): String {
        val s = if (sec < 0) 0 else sec
        return if (s < 60) "$s 秒" else "${s / 60} 分 ${s % 60} 秒"
    }
}
