package tw.plli.streamwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 畫出可以分享的刷歌紀錄小卡（1080 × 1350，適合 Threads／IG） */
object StatsCard {
    const val W = 1080
    const val H = 1350

    private val BG = Color.parseColor("#F7F3EA")
    private val CARD = Color.parseColor("#FFFDF8")
    private val BORDER = Color.parseColor("#ECE6DA")
    private val TEXT = Color.parseColor("#2E2B26")
    private val SUB = Color.parseColor("#7A746A")
    private val GREEN = Color.parseColor("#4E9A5B")
    private val GREEN_BG = Color.parseColor("#DCEBDD")
    private val PURPLE = Color.parseColor("#4B3F72")

    /** 💙💜💗❤️🖤 五個成員代表色 */
    private val MEMBER_COLORS = listOf("#4F8FE8", "#9672D6", "#F08DB5", "#E5484D", "#2E2B26")
        .map { Color.parseColor(it) }

    fun mood(s: StatsSummary): String {
        val t = s.today
        return when {
            t.playSec == 0L -> "今天還沒開始刷歌，等你喔"
            t.playSec >= 12 * 3600 && t.interrupts == 0 -> "整天零中斷，太穩了！"
            t.interrupts == 0 -> "今天零中斷，穩穩的！"
            t.recovered > 0 && t.avgRecoverSec <= 300 -> "斷了也馬上救回來，好樣的！"
            else -> "今天也辛苦了，穩穩刷不要斷"
        }
    }

    fun render(s: StatsSummary): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val normal = Typeface.DEFAULT
        fun paint(size: Float, color: Int, isBold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (isBold) bold else normal
        }
        fun box(l: Float, t: Float, r: Float, b: Float, fill: Int, radius: Float = 40f, stroke: Boolean = true) {
            val rect = RectF(l, t, r, b)
            c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            if (stroke) c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = BORDER
            })
        }

        val pad = 80f

        // ---- 標題 ----
        val dateText = try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.date) ?: Date()
            SimpleDateFormat("yyyy / MM / dd（E）", Locale.TRADITIONAL_CHINESE).format(d)
                .replace("（星期", "（").replace("週", "")
        } catch (_: Exception) {
            s.date
        }
        c.drawText("刷瓜小幫手 · 刷歌紀錄", pad, 140f, paint(34f, SUB, true))
        c.drawText(dateText, pad, 192f, paint(34f, SUB))
        // 右上角成員色小圓點
        var dotX = W - pad - 5 * 30f + 13f
        for (col in MEMBER_COLORS) {
            c.drawCircle(dotX, 128f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
            dotX += 30f
        }
        c.drawText(mood(s), pad, 280f, paint(62f, TEXT, true))

        // ---- 今日播放時數（大卡） ----
        val bigTop = 330f
        val bigBottom = 610f
        box(pad, bigTop, W - pad, bigBottom, CARD)
        c.drawText("今日播放時數", pad + 56f, bigTop + 82f, paint(38f, SUB, true))
        val h = s.today.playSec / 3600
        val m = (s.today.playSec % 3600) / 60
        var x = pad + 50f
        val baseY = bigTop + 235f
        val numP = paint(150f, GREEN, true)
        val unitP = paint(52f, TEXT, true)
        fun seg(num: String, unit: String) {
            c.drawText(num, x, baseY, numP)
            x += numP.measureText(num) + 12f
            c.drawText(unit, x, baseY, unitP)
            x += unitP.measureText(unit) + 34f
        }
        if (h > 0) seg(h.toString(), "小時")
        seg(m.toString(), "分")

        // ---- 四個小格 ----
        val gap = 28f
        val cellW = (W - pad * 2 - gap) / 2
        val cellH = 190f
        val gridTop = bigBottom + gap
        val cells = listOf(
            Triple("最長連續播放", Stats.formatHm(s.today.longestSec), GREEN),
            Triple("中斷次數", "${s.today.interrupts} 次", if (s.today.interrupts == 0) GREEN else TEXT),
            Triple("平均救回時間", if (s.today.recovered > 0) shortDur(s.today.avgRecoverSec) else "—", TEXT),
            Triple("連續刷歌天數", "${s.streakDays} 天", PURPLE)
        )
        cells.forEachIndexed { i, (label, value, color) ->
            val col = i % 2
            val row = i / 2
            val l = pad + col * (cellW + gap)
            val t = gridTop + row * (cellH + gap)
            box(l, t, l + cellW, t + cellH, CARD)
            c.drawText(label, l + 44f, t + 70f, paint(34f, SUB, true))
            val vp = paint(60f, color, true)
            // 太長就縮小
            while (vp.measureText(value) > cellW - 88f && vp.textSize > 30f) vp.textSize -= 2f
            c.drawText(value, l + 44f, t + 150f, vp)
        }

        // ---- 本週累計 ----
        val weekTop = gridTop + 2 * cellH + gap * 2
        box(pad, weekTop, W - pad, weekTop + 100f, GREEN_BG, 32f, stroke = false)
        val weekP = paint(40f, GREEN, true)
        c.drawText("近 7 天累計  ${Stats.formatHm(s.weekPlaySec)}", pad + 44f, weekTop + 64f, weekP)

        // ---- 頁尾 ----
        val foot = paint(30f, SUB)
        val footText = "刷瓜小幫手 · 刷歌停了會通知你的小工具"
        c.drawText(footText, (W - foot.measureText(footText)) / 2, H - 80f, foot)
        return bmp
    }

    private fun shortDur(sec: Long): String =
        if (sec < 60) "$sec 秒" else if (sec < 3600) "${sec / 60} 分 ${sec % 60} 秒" else Stats.formatHm(sec)
}
