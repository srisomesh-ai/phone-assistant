package com.srisomesh.speed

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Live trip state, fed by GPS fixes from [TripService]. GPS needs no internet, so this works
 * offline: on the road, at sea, and on a plane in airplane mode (sit by a window).
 * All speeds are stored in metres/second; the screen converts to the chosen unit.
 */
object TripTracker {
    @Volatile var serviceRunning = false
    @Volatile var paused = false

    @Volatile var currentMs = 0.0
    @Volatile var maxMs = 0.0
    @Volatile var distanceM = 0.0
    @Volatile var movingMillis = 0L
    @Volatile var startedAt = 0L          // elapsedRealtime when the trip started
    @Volatile var pausedMillis = 0L
    private var pauseStart = 0L

    @Volatile var altitudeM: Double? = null
    @Volatile var bearing: Float? = null
    @Volatile var accuracyM: Float? = null
    @Volatile var satsUsed = 0
    @Volatile var satsInView = 0
    @Volatile var lastGpsFix = 0L         // elapsedRealtime of the last GPS fix
    @Volatile var hasAnyFix = false
    @Volatile var place: String? = null   // street / town name, only when online
    @Volatile var lastLocation: Location? = null

    private var prevGood: Location? = null

    private const val MOVING_MS = 0.7     // below this we treat GPS drift as standing still
    private const val MAX_ACCEL = 20.0    // m/s² — reject jumps faster than any car/boat/plane

    fun reset() {
        currentMs = 0.0; maxMs = 0.0; distanceM = 0.0; movingMillis = 0L
        startedAt = SystemClock.elapsedRealtime(); pausedMillis = 0L
        pauseStart = if (paused) startedAt else 0L
        prevGood = null
    }

    fun setPausedState(p: Boolean) {
        if (p == paused) return
        val now = SystemClock.elapsedRealtime()
        if (p) pauseStart = now else if (pauseStart > 0) { pausedMillis += now - pauseStart; pauseStart = 0 }
        paused = p
        prevGood = null
    }

    fun tripMillis(): Long {
        if (startedAt == 0L) return 0
        val now = SystemClock.elapsedRealtime()
        val extra = if (paused && pauseStart > 0) now - pauseStart else 0
        return (now - startedAt - pausedMillis - extra).coerceAtLeast(0)
    }

    fun averageMs(): Double = if (movingMillis > 3000) distanceM / (movingMillis / 1000.0) else 0.0

    /** True when GPS has gone quiet (tunnel, below deck, away from plane window). */
    fun signalLost(): Boolean = !hasAnyFix || SystemClock.elapsedRealtime() - lastGpsFix > 6000

    fun onLocation(loc: Location) {
        lastLocation = loc
        if (loc.provider != LocationManager.GPS_PROVIDER) {
            // Network fixes are too rough for speed; just use them for altitude-less position/place.
            return
        }
        val now = SystemClock.elapsedRealtime()
        hasAnyFix = true
        lastGpsFix = now
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null
        if (loc.hasAltitude()) altitudeM = loc.altitude
        if (loc.hasBearing()) bearing = loc.bearing

        val prev = prevGood
        val dt = if (prev != null) (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9 else 0.0

        var speed = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null && dt > 0.5 -> prev.distanceTo(loc) / dt
            else -> currentMs
        }
        // Reject impossible jumps (a bad fix), keep the last good value.
        if (prev != null && dt > 0 && abs(speed - currentMs) / dt > MAX_ACCEL) speed = currentMs

        // Light smoothing so the needle doesn't jitter.
        currentMs = if (speed < MOVING_MS) 0.0 else currentMs * 0.3 + speed * 0.7
        if (currentMs < MOVING_MS) currentMs = 0.0

        val acc = accuracyM ?: 99f
        if (paused) { prevGood = loc; return }

        if (acc <= 25f && currentMs > maxMs) {
            val sAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else 0f
            if (sAcc < 3f) maxMs = currentMs
        }

        if (acc <= 50f) {
            if (prev != null && dt in 0.0..15.0 && currentMs >= MOVING_MS) {
                distanceM += prev.distanceTo(loc)
                movingMillis += (dt * 1000).toLong()
            }
            prevGood = loc
        }
    }
}
