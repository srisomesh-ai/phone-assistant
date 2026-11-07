package com.srisomesh.speed

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/** Keeps GPS running (screen off too) for the whole journey, with the live speed in a notification. */
class TripService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var lastPlaceLookup = 0L
    private var prefs: Prefs? = null

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            TripTracker.satsUsed = used
            TripTracker.satsInView = status.satelliteCount
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            updateNotification()
            main.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        val type = if (hasLocation(this) && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Waiting for GPS…"), type)

        if (!hasLocation(this)) { stopSelf(); return }
        TripTracker.serviceRunning = true
        TripTracker.paused = false
        TripTracker.reset()   // each start is a new trip
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, this, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnss, main)
        } catch (_: Exception) { }
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        TripTracker.serviceRunning = false
        main.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(this) } catch (_: Exception) { }
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        TripTracker.onLocation(location)
        maybeLookupPlace(location)
    }

    // Older Android versions call these; keep them as no-ops.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }

    /** Street / town name — only when there's internet; skipped silently offline. */
    private fun maybeLookupPlace(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaceLookup < 60_000 || !Geocoder.isPresent() || !NetInfo.isOnline(this)) return
        lastPlaceLookup = now
        Thread {
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                if (a != null) {
                    TripTracker.place = listOfNotNull(a.thoroughfare ?: a.subLocality, a.locality ?: a.subAdminArea, a.countryName)
                        .distinct().joinToString(", ")
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun updateNotification() {
        val u = prefs?.unit ?: SpeedUnit.KMH
        val text = if (TripTracker.signalLost()) "Searching for GPS…"
        else "${Fmt.speed(u.speed(TripTracker.currentMs))} ${u.label} · ${Fmt.dist(TripTracker.distanceM, u)}" +
            (if (TripTracker.paused) " · paused" else "")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trip speed", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Speedometer")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "stop"

        fun hasLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!hasLocation(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripService::class.java))
        }
    }
}
