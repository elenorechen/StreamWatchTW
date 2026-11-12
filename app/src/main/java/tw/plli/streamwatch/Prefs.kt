package tw.plli.streamwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.SecureRandom

/** 所有設定都存在這裡（只存在這支手機上）。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("streamwatch", Context.MODE_PRIVATE)

    // ---- 監控 ----
    var monitoringActive: Boolean
        get() = sp.getBoolean("active", false)
        set(v) = sp.edit().putBoolean("active", v).apply()

    var monitoredPackages: Set<String>
        get() = sp.getStringSet("monitored", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("monitored", v.toSet()).apply()

    var pausedPackages: Set<String>
        get() = sp.getStringSet("paused", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("paused", v.toSet()).apply()

    /** 預設開啟；這裡記的是「關掉自動恢復」的 App。 */
    private var autoResumeOff: Set<String>
        get() = sp.getStringSet("autoResumeOff", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("autoResumeOff", v.toSet()).apply()

    fun isAutoResume(pkg: String) = pkg !in autoResumeOff
    fun setAutoResume(pkg: String, on: Boolean) {
        autoResumeOff = if (on) autoResumeOff - pkg else autoResumeOff + pkg
    }

    fun isPaused(pkg: String) = pkg in pausedPackages
    fun setPaused(pkg: String, paused: Boolean) {
        pausedPackages = if (paused) pausedPackages + pkg else pausedPackages - pkg
    }

    // ---- 通知方式 ----
    /** 停止多久才通知（秒） */
    var thresholdSec: Int
        get() = sp.getInt("threshold", 30)
        set(v) = sp.edit().putInt("threshold", v).apply()

    /** 第一次通知之後，隔多久再提醒（分鐘） */
    var repeatMin: Int
        get() = sp.getInt("repeatMin", 5)
        set(v) = sp.edit().putInt("repeatMin", v).apply()

    /** 同一次停止最多提醒幾次；0 = 一直提醒，直到恢復播放或按「知道了」 */
    var maxAlerts: Int
        get() = sp.getInt("maxAlerts", UNLIMITED)
        set(v) = sp.edit().putInt("maxAlerts", v).apply()

    var alarmSound: Boolean
        get() = sp.getBoolean("alarmSound", false)
        set(v) = sp.edit().putBoolean("alarmSound", v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var notifyRecovery: Boolean
        get() = sp.getBoolean("notifyRecovery", true)
        set(v) = sp.edit().putBoolean("notifyRecovery", v).apply()

    var batteryAlert: Boolean
        get() = sp.getBoolean("batteryAlert", true)
        set(v) = sp.edit().putBoolean("batteryAlert", v).apply()

    var batteryThreshold: Int
        get() = sp.getInt("batteryThreshold", 20)
        set(v) = sp.edit().putInt("batteryThreshold", v).apply()

    var chargeAlert: Boolean
        get() = sp.getBoolean("chargeAlert", true)
        set(v) = sp.edit().putBoolean("chargeAlert", v).apply()

    // ---- 傳送到其他裝置（ntfy） ----
    var remoteEnabled: Boolean
        get() = sp.getBoolean("remote", false)
        set(v) = sp.edit().putBoolean("remote", v).apply()

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: (Build.MODEL ?: "刷歌手機")
        set(v) = sp.edit().putString("deviceName", v).apply()

    var ntfyServer: String
        get() = sp.getString("ntfyServer", null) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("ntfyServer", v.trim().trimEnd('/')).apply()

    /** 連結代碼：第一次讀取時自動產生 */
    val topic: String
        get() {
            val existing = sp.getString("topic", null)
            if (existing != null) return existing
            val t = newTopic()
            sp.edit().putString("topic", t).apply()
            return t
        }

    fun regenerateTopic(): String {
        val t = newTopic()
        sp.edit().putString("topic", t).apply()
        return t
    }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        const val UNLIMITED = 0

        fun maxAlertsLabel(v: Int) = if (v == UNLIMITED) "一直提醒" else "$v 次"
        private const val CHARS = "abcdefghijkmnpqrstuvwxyz23456789" // 去掉容易看錯的 l、o、0、1

        private fun newTopic(): String {
            val rnd = SecureRandom()
            val sb = StringBuilder("plli-")
            repeat(20) { sb.append(CHARS[rnd.nextInt(CHARS.length)]) }
            return sb.toString()
        }
    }
}
