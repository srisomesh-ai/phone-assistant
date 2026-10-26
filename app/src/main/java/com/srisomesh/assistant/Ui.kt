package com.srisomesh.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Density-pixels → pixels. Top-level so it imports cleanly into every screen. */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Small helpers to build the screens in code with the approved look. */
object Ui {
    val BG = Color.parseColor("#F5F4EF")
    val CARD = Color.parseColor("#FFFFFF")
    val SOFT = Color.parseColor("#F1EFE8")
    val TEXT = Color.parseColor("#1F1E1D")
    val MUTED = Color.parseColor("#6B6A65")
    val ACCENT = Color.parseColor("#185FA5")
    val ACCENT_BG = Color.parseColor("#E6F1FB")
    val OK = Color.parseColor("#3B6D11")
    val OK_BG = Color.parseColor("#EAF3DE")
    val WARN = Color.parseColor("#854F0B")
    val LINE = Color.parseColor("#20000000")

    fun card(ctx: Context, radius: Int = 12, bg: Int = CARD): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bg); cornerRadius = ctx.dp(radius).toFloat()
                setStroke(ctx.dp(1), LINE)
            }
            val p = ctx.dp(16)
            setPadding(p, p, p, p)
        }
    }

    fun text(ctx: Context, s: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView {
        return TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun spacer(ctx: Context, h: Int): View = View(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(1, ctx.dp(h))
    }
}
