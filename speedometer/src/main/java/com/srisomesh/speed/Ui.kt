package com.srisomesh.speed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Dark, high-contrast look: easy to read on a dashboard, at sea, or at night. */
object Ui {
    val BG = Color.parseColor("#0E1116")
    val CARD = Color.parseColor("#171B22")
    val SOFT = Color.parseColor("#222833")
    val TEXT = Color.parseColor("#F2F4F7")
    val MUTED = Color.parseColor("#8A93A3")
    val ACCENT = Color.parseColor("#3DDC97")
    val ACCENT2 = Color.parseColor("#5AB0FF")
    val WARN = Color.parseColor("#FFB547")
    val BAD = Color.parseColor("#FF6B6B")
    val LINE = Color.parseColor("#2A303B")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 16, LINE)
        val p = ctx.dp(16); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(12) }
    }

    fun rounded(ctx: Context, color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = ctx.dp(radius).toFloat()
        if (stroke != null) setStroke(ctx.dp(1), stroke)
    }

    fun text(ctx: Context, s: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** A row of pill buttons where one is selected (modes, units, tabs). */
    fun chips(ctx: Context, labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val r = row(ctx)
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val chip = text(ctx, label, 14f, if (on) BG else TEXT, bold = on).apply {
                gravity = Gravity.CENTER
                background = rounded(ctx, if (on) ACCENT else SOFT, 20)
                setPadding(ctx.dp(8), ctx.dp(9), ctx.dp(8), ctx.dp(9))
                setOnClickListener { onPick(i) }
            }
            r.addView(chip, lp(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = ctx.dp(6)
            })
        }
        return r
    }

    fun button(ctx: Context, label: String, color: Int, onClick: () -> Unit): TextView =
        text(ctx, label, 16f, BG, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(ctx, color, 14)
            setPadding(ctx.dp(12), ctx.dp(14), ctx.dp(12), ctx.dp(14))
            setOnClickListener { onClick() }
        }

    /** Small "label / big value" tile. Returns the tile and the value TextView to update. */
    fun stat(ctx: Context, label: String): Pair<View, TextView> {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, SOFT, 12)
            val p = ctx.dp(10); setPadding(p, p, p, p)
        }
        box.addView(text(ctx, label, 12f, MUTED))
        val v = text(ctx, "–", 18f, TEXT, bold = true).apply { setPadding(0, ctx.dp(2), 0, 0) }
        box.addView(v)
        return box to v
    }

    /** Grid of stat tiles, [cols] per row. */
    fun grid(ctx: Context, tiles: List<View>, cols: Int): LinearLayout {
        val g = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(cols).forEachIndexed { ri, chunk ->
            val r = row(ctx)
            chunk.forEachIndexed { i, t ->
                r.addView(t, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = ctx.dp(8) })
            }
            repeat(cols - chunk.size) { r.addView(View(ctx), lp(0, 1, 1f).apply { leftMargin = ctx.dp(8) }) }
            g.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (ri > 0) topMargin = ctx.dp(8) })
        }
        return g
    }

    fun section(ctx: Context, s: String): TextView = text(ctx, s, 13f, MUTED, bold = true).apply {
        setPadding(0, ctx.dp(14), 0, ctx.dp(8))
    }

    /** "Label ........ value" line. Returns the value view. */
    fun kv(ctx: Context, parent: LinearLayout, label: String): TextView {
        val r = row(ctx).apply { setPadding(0, ctx.dp(5), 0, ctx.dp(5)) }
        r.addView(text(ctx, label, 14f, MUTED), lp(0, WRAP, 1f))
        val v = text(ctx, "–", 14f, TEXT, bold = true).apply { gravity = Gravity.END }
        r.addView(v, lp(0, WRAP, 1.4f))
        parent.addView(r)
        return v
    }
}
