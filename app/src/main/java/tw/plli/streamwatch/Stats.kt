package tw.plli.streamwatch

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 一天的刷歌紀錄（秒） */
data class DayStats(
    var playSec: Long = 0,
    var longestSec: Long = 0,
    var interrupts: Int = 0,
    var recovered: Int = 0,
    var downtimeSec: Long = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("p", playSec).put("l", longestSec).put("i", interrupts)
        .put("r", recovered).put("d", downtimeSec)

    val avgRecoverSec: Long get() = if (recovered > 0) downtimeSec / recovered else 0

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) DayStats() else DayStats(
            o.optLong("p"), o.optLong("l"), o.optInt("i"), o.optInt("r"), o.optLong("d")
        )
    }
}

/** 給小卡用的摘要 */
data class StatsSummary(
    val date: String,
    val today: DayStats,
    val weekPlaySec: Long,
    val streakDays: Int,
    val currentStretchSec: Long
)

/**
 * 記錄刷歌數據。只存在這支手機上，只有時數和次數，不記錄歌曲或帳號。
 * 由 MonitorEngine 每次 tick 呼叫 update()。
 */
object Stats {
    /** 一天播放超過這個時間，才算「有刷歌的一天」 */
    const val STREAK_MIN_SEC = 3600L
    private const val KEEP_DAYS = 60
    private const val MAX_GAP_MS = 15_000L // 兩次檢查間隔太久（手機休眠、服務被停）就不算

    private var loaded = false
    private val days = LinkedHashMap<String, DayStats>()
    private var lastAt = 0L
    private var lastPlaying = false
    private var stretchSec = 0L
    private var badStartAt = 0L
    private var badCounted = false
    private var dirty = 0

    private fun dayKey(ms: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE).getString("days", null) ?: return
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) days[k] = DayStats.fromJson(o.optJSONObject(k))
        } catch (_: Exception) {
        }
    }

    private fun save(ctx: Context) {
        val keys = days.keys.sorted()
        if (keys.size > KEEP_DAYS) keys.take(keys.size - KEEP_DAYS).forEach { days.remove(it) }
        val o = JSONObject()
        for ((k, v) in days) o.put(k, v.toJson())
        ctx.getSharedPreferences("stats", Context.MODE_PRIVATE).edit().putString("days", o.toString()).apply()
        dirty = 0
    }

    private fun today(): DayStats = days.getOrPut(dayKey()) { DayStats() }

    /**
     * @param counting 是否在監控中（停止監控或全部暫停時不記錄）
     * @param playing  是否有任何一個監控中的 App 正在播放
     * @param thresholdSec 停多久才算一次「中斷」（跟通知門檻一樣）
     */
    fun update(ctx: Context, nowElapsed: Long, counting: Boolean, playing: Boolean, thresholdSec: Int) {
        load(ctx)
        val dt = if (lastAt == 0L) 0L else nowElapsed - lastAt
        lastAt = nowElapsed
        val validGap = dt in 1..MAX_GAP_MS

        if (!counting) {
            if (stretchSec > 0 || badStartAt != 0L) dirty++
            stretchSec = 0
            badStartAt = 0
            badCounted = false
            lastPlaying = false
            if (dirty > 0) save(ctx)
            return
        }

        val t = today()
        if (playing) {
            if (validGap && lastPlaying) {
                val add = dt / 1000.0
                t.playSec += Math.round(add)
                stretchSec += Math.round(add)
            }
            if (stretchSec > t.longestSec) t.longestSec = stretchSec
            if (badStartAt != 0L) {
                // 從停止恢復
                if (badCounted) {
                    t.recovered++
                    t.downtimeSec += (nowElapsed - badStartAt) / 1000
                }
                badStartAt = 0
                badCounted = false
                dirty += 10
            }
        } else {
            // 換歌時的短暫緩衝不算中斷，也不打斷「連續播放」；停到通知門檻才算一次中斷
            if (lastPlaying) dirty += 10
            if (badStartAt == 0L) badStartAt = nowElapsed
            if (!badCounted && nowElapsed - badStartAt >= thresholdSec * 1000L) {
                badCounted = true
                stretchSec = 0
                t.interrupts++
                dirty += 10
            }
        }
        lastPlaying = playing
        dirty++
        if (dirty >= 10) save(ctx) // 大約每 30 秒存一次，狀態改變時馬上存
    }

    fun summary(ctx: Context): StatsSummary {
        load(ctx)
        val cal = Calendar.getInstance()
        val todayKey = dayKey(cal.timeInMillis)
        val today = days[todayKey]?.copy() ?: DayStats()

        var week = 0L
        val c = Calendar.getInstance()
        repeat(7) {
            week += days[dayKey(c.timeInMillis)]?.playSec ?: 0
            c.add(Calendar.DAY_OF_YEAR, -1)
        }

        // 連續天數：今天還沒滿一小時的話，從昨天開始算
        var streak = 0
        val s = Calendar.getInstance()
        if ((days[todayKey]?.playSec ?: 0) < STREAK_MIN_SEC) s.add(Calendar.DAY_OF_YEAR, -1)
        while ((days[dayKey(s.timeInMillis)]?.playSec ?: 0) >= STREAK_MIN_SEC) {
            streak++
            s.add(Calendar.DAY_OF_YEAR, -1)
        }
        return StatsSummary(todayKey, today, week, streak, stretchSec)
    }

    fun formatHm(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 -> "$h 小時 $m 分"
            m > 0 -> "$m 分鐘"
            else -> "${sec} 秒"
        }
    }
}
