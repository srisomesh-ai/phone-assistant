package com.srisomesh.speed

import kotlin.math.ceil
import kotlin.math.roundToInt

/** How you're travelling. Each picks sensible units and a gauge range. */
enum class Mode(val label: String, val emoji: String, val defaultUnit: SpeedUnit, val gaugeMaxMs: Double) {
    ROAD("Road", "🚗", SpeedUnit.KMH, 200 / 3.6),
    SEA("Sea", "⛴", SpeedUnit.KNOTS, 50 * 0.514444),
    FLIGHT("Flight", "✈", SpeedUnit.KMH, 1000 / 3.6),
}

enum class SpeedUnit(val label: String, val perMs: Double, val distLabel: String, val metersPerDist: Double, val feet: Boolean) {
    KMH("km/h", 3.6, "km", 1000.0, false),
    MPH("mph", 2.236936, "mi", 1609.344, true),
    KNOTS("knots", 1.943844, "nmi", 1852.0, true),
    MS("m/s", 1.0, "km", 1000.0, false);

    fun speed(ms: Double) = ms * perMs
    fun dist(m: Double) = m / metersPerDist

    /** Round the gauge max up to a tidy number in this unit. */
    fun niceMax(ms: Double): Int {
        val v = speed(ms)
        val step = when { v > 400 -> 100.0; v > 150 -> 20.0; v > 60 -> 10.0; else -> 5.0 }
        return (ceil(v / step) * step).toInt()
    }
}

object Fmt {
    fun speed(v: Double): String = if (v < 10) String.format("%.1f", v) else v.roundToInt().toString()

    fun dist(m: Double, u: SpeedUnit): String {
        val d = u.dist(m)
        return if (d < 10) String.format("%.2f %s", d, u.distLabel) else String.format("%.1f %s", d, u.distLabel)
    }

    fun altitude(m: Double?, u: SpeedUnit): String {
        if (m == null) return "–"
        return if (u.feet) "${(m * 3.28084).roundToInt()} ft" else "${m.roundToInt()} m"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    fun heading(deg: Float?): String {
        if (deg == null) return "–"
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return "${dirs[((deg + 22.5f) / 45f).toInt() % 8]} ${deg.roundToInt()}°"
    }

    fun mbps(v: Double): String = when {
        v <= 0 -> "–"
        v < 10 -> String.format("%.2f", v)
        v < 100 -> String.format("%.1f", v)
        else -> v.roundToInt().toString()
    }
}
