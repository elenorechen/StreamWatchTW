package tw.plli.streamwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 負責發出通知：本機通知、鬧鐘音效、以及透過 ntfy 傳到其他裝置。 */
object Alerts {
    const val CH_ALERT = "alert_v1"
    const val CH_ALERT_QUIET = "alert_novibe_v1"
    const val CH_INFO = "info_v1"
    const val CH_STATUS = "status_v1"

    const val ID_ONGOING = 1
    const val ID_BATTERY = 2
    const val ID_CHARGE = 3
    const val ID_TEST = 4

    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alert = NotificationChannel(CH_ALERT, "刷歌停止提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "音樂 App 停止播放時的提醒（會震動）"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        }
        val quiet = NotificationChannel(CH_ALERT_QUIET, "刷歌停止提醒（不震動）", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "音樂 App 停止播放時的提醒（不震動）"
            enableVibration(false)
        }
        val info = NotificationChannel(CH_INFO, "一般訊息", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "恢復播放、測試通知等"
        }
        val status = NotificationChannel(CH_STATUS, "監控中狀態", NotificationManager.IMPORTANCE_MIN).apply {
            description = "常駐顯示目前正在監控"
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(alert, quiet, info, status))
    }

    /** 常駐的「監控中」通知；count = null 表示收起來 */
    fun showOngoing(ctx: Context, count: Int?) {
        if (count == null) {
            cancel(ctx, ID_ONGOING)
            return
        }
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("刷瓜小幫手監控中")
            .setContentText("正在監控 $count 個 App")
            .setOngoing(true)
            .setContentIntent(openMainIntent(ctx))
            .build()
        notify(ctx, ID_ONGOING, n)
    }

    fun notificationIdFor(pkg: String) = 1000 + (pkg.hashCode() and 0x7fffffff) % 100000

    /** 刷歌停止的主要提醒 */
    fun alertStopped(ctx: Context, prefs: Prefs, pkg: String, appName: String, title: String, body: String) {
        ensureChannels(ctx)
        val channel = if (prefs.vibrate) CH_ALERT else CH_ALERT_QUIET
        val id = notificationIdFor(pkg)

        val builder = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openMainIntent(ctx))

        // 「開啟 App」按鈕：直接跳到那個音樂 App
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                ctx, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "開啟 $appName", pi).build())
        }

        // 「知道了」按鈕：這次停止不再重複提醒
        val ack = Intent(ctx, AckReceiver::class.java).putExtra(AckReceiver.EXTRA_PKG, pkg)
        val ackPi = PendingIntent.getBroadcast(
            ctx, id, ack,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(Notification.Action.Builder(null, "知道了", ackPi).build())

        // 先收掉上一則再重新發：同一則通知原地更新時，手機不會再跳出來，重複提醒就像沒發一樣
        cancel(ctx, id)
        builder.setWhen(System.currentTimeMillis()).setShowWhen(true)
        notify(ctx, id, builder.build())
        if (prefs.alarmSound) playAlarm(ctx)
        sendRemote(prefs, title, body, priority = 5, tags = listOf("warning"))
    }

    fun infoNotify(ctx: Context, prefs: Prefs, id: Int, title: String, body: String, important: Boolean, remote: Boolean = true) {
        ensureChannels(ctx)
        val channel = when {
            !important -> CH_INFO
            prefs.vibrate -> CH_ALERT
            else -> CH_ALERT_QUIET
        }
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openMainIntent(ctx))
            .build()
        notify(ctx, id, n)
        if (important && prefs.alarmSound) playAlarm(ctx)
        if (remote) {
            sendRemote(
                prefs, title, body,
                priority = if (important) 5 else 3,
                tags = listOf(if (important) "warning" else "white_check_mark")
            )
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // 沒有通知權限時，至少遠端通知還會送出
        }
    }

    private fun openMainIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 用鬧鐘音量響 10 秒 */
    private fun playAlarm(ctx: Context) {
        main.post {
            try {
                ringtone?.stop()
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return@post
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                r.play()
                ringtone = r
                main.postDelayed({ try { r.stop() } catch (_: Exception) {} }, 10_000)
            } catch (_: Exception) {
            }
        }
    }

    fun stopAlarm() {
        main.post { try { ringtone?.stop() } catch (_: Exception) {} }
    }

    /**
     * 透過 ntfy 傳到日常手機和電腦。
     * 用 JSON 格式送出，中文標題才不會變亂碼。
     */
    fun sendRemote(
        prefs: Prefs,
        title: String,
        body: String,
        priority: Int,
        tags: List<String>,
        force: Boolean = false,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        if (!prefs.remoteEnabled && !force) return
        val server = prefs.ntfyServer
        val topic = prefs.topic
        val device = prefs.deviceName
        net.execute {
            var ok = false
            var msg: String
            try {
                val json = JSONObject()
                    .put("topic", topic)
                    .put("title", title)
                    .put("message", "【$device】$body")
                    .put("priority", priority)
                    .put("tags", JSONArray(tags))
                val conn = URL("$server/").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                ok = code in 200..299
                msg = if (ok) "已送出" else "伺服器回應錯誤（$code）"
            } catch (e: Exception) {
                msg = "送出失敗：${e.javaClass.simpleName}（請確認手機有網路）"
            }
            val finalOk = ok
            val finalMsg = msg
            onResult?.let { cb -> main.post { cb(finalOk, finalMsg) } }
        }
    }
}
