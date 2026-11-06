package tw.plli.streamwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import java.util.Calendar

/** 畫出可以分享的刷歌紀錄小卡（Y2K 風格，1080 × 1350，跟電腦版同一個設計） */
object StatsCard {
    const val W = 1080
    const val H = 1350

    private fun c(hex: String) = Color.parseColor(hex)
    private val INK = c("#3B2F63")
    private val SUB = c("#7A6CA8")
    private val WHITE = c("#FFFDFE")
    private val PINK = c("#FF6FB1")
    private val BG_TOP = c("#E6DDFF")
    private val BG_MID = c("#FFE1F0")
    private val BG_BOT = c("#D9EEFF")
    private val BAR_L = c("#FFB8DA")
    private val BAR_R = c("#B9DCFF")
    private val MINT = c("#D5F5E3")
    private val LEMON = c("#FFF2B3")
    private val ROSE = c("#FFDCEB")
    private val LILAC = c("#E5DCFF")
    private val MARKER = c("#FFF09A")

    /** 💙💜💗❤️🖤 五個成員代表色 */
    private val MEMBER_COLORS = listOf("#4F8FE8", "#9672D6", "#F08DB5", "#E5484D", "#2E2B26").map { c(it) }

    private val SPARKLES = listOf(
        floatArrayOf(70f, 330f, 22f) to "#FFFFFF", floatArrayOf(1015f, 300f, 30f) to "#FFFFFF",
        floatArrayOf(985f, 640f, 18f) to "#FFD1E8", floatArrayOf(45f, 760f, 26f) to "#FFFFFF",
        floatArrayOf(1040f, 990f, 24f) to "#FFFFFF", floatArrayOf(60f, 1180f, 18f) to "#C9B8FF",
        floatArrayOf(960f, 1290f, 28f) to "#FFFFFF", floatArrayOf(150f, 1300f, 16f) to "#FFFFFF",
        floatArrayOf(540f, 1312f, 14f) to "#FFC4E1"
    )

    fun mood(s: StatsSummary): String {
        val t = s.today
        return when {
            t.playSec == 0L -> "今天還沒開刷唷 ☆彡"
            t.playSec >= 12 * 3600 && t.interrupts == 0 -> "整天零中斷！超神der ☆彡"
            t.interrupts == 0 -> "零中斷達成 ♡ 讚啦～"
            t.recovered > 0 && t.avgRecoverSec <= 300 -> "斷線秒救回 ♡ 超強der"
            else -> "今日刷歌任務完成 ☆彡"
        }
    }

    private val heavy: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 900, false)
        else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private fun textPaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = heavy
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun stroke(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = INK
        strokeJoin = Paint.Join.ROUND
    }

    /** 貼紙風格的方塊：硬陰影＋粗外框 */
    private fun Canvas.sticker(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, shadow: Float = 10f, line: Float = 5f) {
        if (shadow > 0) drawRoundRect(RectF(l + shadow, t + shadow, r + shadow, b + shadow), radius, radius, fill(INK))
        drawRoundRect(RectF(l, t, r, b), radius, radius, fill(color))
        drawRoundRect(RectF(l, t, r, b), radius, radius, stroke(line))
    }

    /** 四角星 ✦ */
    private fun Canvas.sparkle(x: Float, y: Float, r: Float, color: Int) {
        val k = r * 0.28f
        val p = Path().apply {
            moveTo(x, y - r)
            quadTo(x + k, y - k, x + r, y)
            quadTo(x + k, y + k, x, y + r)
            quadTo(x - k, y + k, x - r, y)
            quadTo(x - k, y - k, x, y - r)
            close()
        }
        drawPath(p, fill(color))
    }

    /** 愛心 ♥ */
    private fun Canvas.heart(x: Float, y: Float, s: Float, color: Int) {
        val p = Path().apply {
            moveTo(x, y + s * 0.45f)
            cubicTo(x - s * 0.95f, y - s * 0.15f, x - s * 0.5f, y - s * 0.85f, x, y - s * 0.35f)
            cubicTo(x + s * 0.5f, y - s * 0.85f, x + s * 0.95f, y - s * 0.15f, x, y + s * 0.45f)
            close()
        }
        drawPath(p, fill(color))
        drawPath(p, stroke(3f))
    }

    /** 外框字（泡泡字），回傳寬度 */
    private fun Canvas.bubbleText(text: String, x: Float, y: Float, size: Float, color: Int, line: Float): Float {
        val st = textPaint(size, INK).apply {
            style = Paint.Style.STROKE
            strokeWidth = line
            strokeJoin = Paint.Join.ROUND
        }
        drawText(text, x, y, st)
        val f = textPaint(size, color)
        drawText(text, x, y, f)
        return f.measureText(text)
    }

    private fun fit(p: Paint, text: String, maxW: Float, min: Float): Paint {
        while (p.measureText(text) > maxW && p.textSize > min) p.textSize -= 2f
        return p
    }

    private fun dateLabel(key: String): String {
        val parts = key.split("-").map { it.toIntOrNull() ?: 0 }
        if (parts.size != 3) return key
        val cal = Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2]) }
        val wk = "日一二三四五六"[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "%d.%02d.%02d（%s）".format(parts[0], parts[1], parts[2], wk)
    }

    fun render(s: StatsSummary): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)

        // ---- 背景：粉彩漸層＋小圓點＋星星 ----
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(BG_TOP, BG_MID, BG_BOT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val dot = fill(Color.argb(140, 255, 255, 255))
        var yy = 40
        while (yy < H) {
            var xx = if ((yy / 70) % 2 == 1) 40 else 75
            while (xx < W) {
                cv.drawCircle(xx.toFloat(), yy.toFloat(), 3f, dot)
                xx += 70
            }
            yy += 70
        }
        for ((p, col) in SPARKLES) cv.sparkle(p[0], p[1], p[2], c(col))

        // ---- 頂部貼紙：名稱＋愛心 ----
        val tagP = textPaint(34f, INK)
        val tag = "刷瓜小幫手 ☆ 刷歌紀錄"
        val tagW = tagP.measureText(tag) + 60f
        cv.sticker(60f, 58f, 60f + tagW, 128f, 35f, WHITE, 6f, 4f)
        cv.drawText(tag, 90f, 105f, tagP)
        var hx = W - 60f - 4 * 52f - 22f
        for (col in MEMBER_COLORS) {
            cv.heart(hx, 96f, 40f, col)
            hx += 52f
        }

        // ---- 視窗 ----
        val wl = 60f
        val wt = 165f
        val wr = W - 72f
        val wb = 1215f
        cv.sticker(wl, wt, wr, wb, 34f, WHITE, 14f, 6f)
        val barPath = Path().apply {
            addRoundRect(RectF(wl, wt, wr, wt + 82f), floatArrayOf(34f, 34f, 34f, 34f, 0f, 0f, 0f, 0f), Path.Direction.CW)
        }
        cv.drawPath(barPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(wl, 0f, wr, 0f, BAR_L, BAR_R, Shader.TileMode.CLAMP)
        })
        cv.drawLine(wl, wt + 82f, wr, wt + 82f, stroke(6f))
        cv.drawRoundRect(RectF(wl, wt, wr, wb), 34f, 34f, stroke(6f))
        listOf("#FF8FC2", "#FFE27A", "#8EE3B5").forEachIndexed { i, col ->
            val cx = wl + 48f + i * 44f
            cv.drawCircle(cx, wt + 41f, 14f, fill(c(col)))
            cv.drawCircle(cx, wt + 41f, 14f, stroke(4f))
        }
        val dateP = textPaint(32f, INK)
        val dl = dateLabel(s.date)
        cv.drawText(dl, wr - 40f - dateP.measureText(dl), wt + 53f, dateP)

        val ix = wl + 50f
        val iw = wr - wl - 100f

        // ---- 標語（螢光筆底線） ----
        val mood = mood(s)
        val moodP = fit(textPaint(64f, INK), mood, iw, 36f)
        val moodW = moodP.measureText(mood)
        cv.drawRoundRect(RectF(ix - 8f, 338f - moodP.textSize * 0.42f, ix + moodW + 8f, 350f), 10f, 10f, fill(MARKER))
        cv.drawText(mood, ix, 338f, moodP)

        // ---- 今日播放時數 ----
        val labP = textPaint(32f, INK)
        val lab = "♪ 今日播放時數"
        val labW = labP.measureText(lab) + 44f
        cv.sticker(ix, 392f, ix + labW, 446f, 27f, LILAC, 0f, 4f)
        cv.drawText(lab, ix + 22f, 431f, labP)
        val total = s.today.playSec
        val h = total / 3600
        val m = (total % 3600) / 60
        var x = ix
        val baseY = 610f
        val unitP = textPaint(54f, INK)
        fun seg(num: String, unit: String) {
            x += cv.bubbleText(num, x, baseY, 160f, PINK, 12f) + 14f
            cv.drawText(unit, x, baseY, unitP)
            x += unitP.measureText(unit) + 30f
        }
        if (h > 0) seg(h.toString(), "小時")
        seg(m.toString(), "分")

        // ---- 四張貼紙 ----
        val gap = 34f
        val cellW = (iw - gap) / 2
        val cellH = 178f
        val top = 662f
        val t = s.today
        val cells = listOf(
            arrayOf("最長連續播放", Stats.formatHm(t.longestSec), "♪"),
            arrayOf("中斷次數", "${t.interrupts} 次", "☆"),
            arrayOf("平均救回時間", if (t.recovered > 0) shortDur(t.avgRecoverSec) else "—", "♡"),
            arrayOf("連續刷歌天數", "${s.streakDays} 天", "★")
        )
        val fills = listOf(MINT, LEMON, ROSE, LILAC)
        cells.forEachIndexed { i, (label, value, icon) ->
            val l = ix + (i % 2) * (cellW + gap)
            val tp = top + (i / 2) * (cellH + gap)
            cv.sticker(l, tp, l + cellW, tp + cellH, 26f, fills[i], 8f, 5f)
            cv.drawText(label, l + 32f, tp + 62f, textPaint(30f, SUB))
            cv.drawText(icon, l + cellW - 66f, tp + 64f, textPaint(40f, INK))
            cv.drawText(value, l + 32f, tp + 140f, fit(textPaint(58f, INK), value, cellW - 64f, 28f))
        }

        // ---- 近 7 天累計 ----
        val wkTop = top + 2 * cellH + gap + 34f
        val wkRect = RectF(ix, wkTop, ix + iw, wkTop + 84f)
        cv.drawRoundRect(wkRect, 42f, 42f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(ix, 0f, ix + iw, 0f, BAR_R, BAR_L, Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(wkRect, 42f, 42f, stroke(5f))
        val wkText = "近 7 天累計 ☆ ${Stats.formatHm(s.weekPlaySec)}"
        cv.drawText(wkText, ix + 40f, wkTop + 56f, fit(textPaint(38f, INK), wkText, iw - 80f, 26f))

        // ---- 頁尾 ----
        val footP = textPaint(30f, INK)
        val foot = "刷瓜小幫手 ・ 刷歌停了會通知你 ♡"
        cv.drawText(foot, (W - footP.measureText(foot)) / 2, 1290f, footP)
        return bmp
    }

    private fun shortDur(sec: Long): String =
        if (sec < 60) "$sec 秒" else if (sec < 3600) "${sec / 60} 分 ${sec % 60} 秒" else Stats.formatHm(sec)
}
