package com.srisomesh.speed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A 270° speedometer dial with ticks, a coloured progress arc and a big centre number. */
class GaugeView(ctx: Context) : View(ctx) {

    var max: Int = 200; set(v) { field = v.coerceAtLeast(1); invalidate() }
    var value: Double = 0.0; set(v) { field = v; invalidate() }
    var bigText: String = "0"; set(v) { field = v; invalidate() }
    var unitText: String = "km/h"; set(v) { field = v; invalidate() }
    var subText: String = ""; set(v) { field = v; invalidate() }
    var color: Int = Ui.ACCENT; set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.SOFT; strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textAlign = Paint.Align.CENTER }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val size = min(width, context.dp(320))
        setMeasuredDimension(width, (size * 0.9f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val size = min(width.toFloat(), height / 0.9f)
        val stroke = size * 0.06f
        val cx = width / 2f
        val cy = size * 0.5f
        val r = size / 2f - stroke - size * 0.02f
        oval.set(cx - r, cy - r, cx + r, cy + r)

        track.strokeWidth = stroke
        c.drawArc(oval, 135f, 270f, false, track)

        val frac = (value / max).coerceIn(0.0, 1.0).toFloat()
        arc.strokeWidth = stroke; arc.color = color
        if (frac > 0f) c.drawArc(oval, 135f, 270f * frac, false, arc)

        // ticks + labels at 0, 1/4 .. max
        tick.strokeWidth = size * 0.008f
        label.textSize = size * 0.05f
        for (i in 0..10) {
            val a = Math.toRadians(135.0 + 27.0 * i)
            val inner = r - stroke * (if (i % 2 == 0) 1.4f else 1.0f)
            val outer = r - stroke * 0.7f
            c.drawLine(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat(), tick)
            if (i % 2 == 0) {
                val lr = r - stroke * 2.4f
                val v = max * i / 10
                c.drawText(v.toString(), cx + (lr * cos(a)).toFloat(),
                    cy + (lr * sin(a)).toFloat() + label.textSize / 3, label)
            }
        }

        big.textSize = size * 0.22f
        c.drawText(bigText, cx, cy + big.textSize * 0.32f, big)
        small.textSize = size * 0.065f
        c.drawText(unitText, cx, cy + big.textSize * 0.32f + small.textSize * 1.4f, small)
        if (subText.isNotEmpty()) {
            small.textSize = size * 0.05f
            c.drawText(subText, cx, cy + r * 0.88f, small)
        }
    }
}
