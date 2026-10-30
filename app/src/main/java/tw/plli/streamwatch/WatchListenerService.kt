package tw.plli.streamwatch

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService

/**
 * 開啟「通知存取權」之後，系統會一直讓這個服務在背景執行。
 * 我們用它來讀取各音樂 App 的播放狀態（MediaSession），
 * 不會讀取或保存任何其他 App 的通知內容。
 */
class WatchListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastOngoingCount: Int? = -1

    private val loop = object : Runnable {
        override fun run() {
            tickOnce()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MonitorEngine.listenerConnected = true
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MonitorEngine.listenerConnected = false
        handler.removeCallbacks(loop)
        releaseWakeLock()
        // 被系統斷開時，請系統重新連上
        try {
            NotificationListenerService.requestRebind(ComponentName(this, WatchListenerService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        releaseWakeLock()
        MonitorEngine.listenerConnected = false
        super.onDestroy()
    }

    private fun tickOnce() {
        val controllers: List<MediaController>? = try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(this, WatchListenerService::class.java))
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }

        try {
            MonitorEngine.tick(this, controllers)
        } catch (_: Exception) {
            // 不讓單次錯誤把整個監控停掉
        }

        val prefs = Prefs(this)
        val count = if (prefs.monitoringActive) prefs.monitoredPackages.size else null
        if (count != lastOngoingCount) {
            Alerts.showOngoing(this, count)
            lastOngoingCount = count
        }
        if (prefs.monitoringActive) holdWakeLock() else releaseWakeLock()
    }

    /**
     * 音樂停掉之後手機可能會進入休眠，計時就會跟著停。
     * 監控中時保持 CPU 醒著（螢幕不會亮），確保時間到一定會通知。
     */
    private fun holdWakeLock() {
        val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamWatchTW:monitor")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        wl.acquire(WAKELOCK_MS) // 每次 tick 都會延長
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val TICK_MS = 3_000L
        const val WAKELOCK_MS = 60_000L

        /** 打開 App 時呼叫，萬一服務被系統斷開，請它重新連上 */
        fun requestRebindIfNeeded(ctx: Context) {
            if (!MonitorEngine.listenerConnected) {
                try {
                    NotificationListenerService.requestRebind(ComponentName(ctx, WatchListenerService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }
}
