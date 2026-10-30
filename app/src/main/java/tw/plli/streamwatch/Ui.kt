package tw.plli.streamwatch

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 介面配色與小工具（不使用任何外部套件，打包比較不會出錯） */
object Pal {
    val bg = Color.parseColor("#F7F3EA")
    val card = Color.parseColor("#FFFDF8")
    val border = Color.parseColor("#ECE6DA")
    val text = Color.parseColor("#2E2B26")
    val sub = Color.parseColor("#7A746A")
    val green = Color.parseColor("#DCEBDD")
    val greenText = Color.parseColor("#2F5D3A")
    val greenStrong = Color.parseColor("#4E9A5B")
    val purple = Color.parseColor("#E6E0F3")
    val purpleText = Color.parseColor("#4B3F72")
    val warnBg = Color.parseColor("#FBE9D7")
    val warnText = Color.parseColor("#8A4B12")
    val okText = Color.parseColor("#2E6B34")

    fun chip(state: WatchState): Pair<Int, Int> = when (state) {
        WatchState.PLAYING -> Color.parseColor("#DCEFD9") to Color.parseColor("#2E6B34")
        WatchState.BUFFERING -> Color.parseColor("#ECE8E1") to Color.parseColor("#6B6457")
        WatchState.STOPPED -> Color.parseColor("#F8D9CC") to Color.parseColor("#9A3F1F")
        WatchState.UNKNOWN -> Color.parseColor("#E9E6E1") to Color.parseColor("#5E5A54")
        WatchState.PAUSED -> Color.parseColor("#E6E0F3") to Color.parseColor("#4B3F72")
        WatchState.IDLE -> Color.parseColor("#ECEAE6") to Color.parseColor("#6B6B6B")
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

fun Context.label(
    text: CharSequence,
    sizeSp: Float = 15f,
    color: Int = Pal.text,
    bold: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(0f, 1.15f)
}

fun Context.pillButton(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
    label(text, 15f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(bg, 14)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.cardBox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Pal.card, 20, Pal.border)
    setPadding(dp(16), dp(16), dp(16), dp(16))
}

fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height, weight)

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun View.margins(ctx: Context, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): View {
    val p = (layoutParams as? LinearLayout.LayoutParams) ?: lp(MATCH, WRAP)
    p.setMargins(ctx.dp(start), ctx.dp(top), ctx.dp(end), ctx.dp(bottom))
    layoutParams = p
    return this
}
