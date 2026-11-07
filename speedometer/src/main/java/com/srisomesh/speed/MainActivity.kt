package com.srisomesh.speed

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two tabs:
 *  - Travel speed: GPS speedometer for road / sea / flight, works fully offline.
 *  - Internet speed: Wi-Fi / mobile connection details, live usage, and a speed test.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var tabHolder: LinearLayout
    private var ticker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16); setPadding(p, p, p, p)
        }
        root.addView(Ui.text(this, "Speedometer", 22f, Ui.TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        })
        tabHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabHolder, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(12) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        scroll.addView(root)
        setContentView(scroll)
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        showTab(prefs.tab)
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    override fun onDestroy() {
        test?.cancel()
        super.onDestroy()
    }

    private fun showTab(i: Int) {
        prefs.tab = i
        tabHolder.removeAllViews()
        tabRow = Ui.chips(this, listOf("🚗  Travel speed", "📶  Internet speed"), i) { showTab(it) }
        tabHolder.addView(tabRow)
        stopTicker()
        content.removeAllViews()
        if (i == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            buildTravel()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            buildInternet()
        }
    }

    private fun startTicker(periodMs: Long, f: () -> Unit) {
        stopTicker()
        val r = object : Runnable {
            override fun run() { f(); main.postDelayed(this, periodMs) }
        }
        ticker = r
        main.post(r)
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    // ======================================================================
    // TRAVEL SPEED
    // ======================================================================

    private fun buildTravel() {
        if (!TripService.hasLocation(this)) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, "Location permission needed", 17f, Ui.TEXT, bold = true))
            c.addView(Ui.text(this, "Travel speed comes from GPS satellites. It works without internet " +
                "— on the road, at sea, and on flights in airplane mode.", 14f, Ui.MUTED).apply {
                setPadding(0, dp(6), 0, dp(12))
            })
            c.addView(Ui.button(this, "Allow location", Ui.ACCENT) { askPermissions() })
            content.addView(c)
            return
        }

        val lm = getSystemService(LocationManager::class.java)
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, "⚠  Location (GPS) is off", 16f, Ui.WARN, bold = true))
            c.addView(Ui.text(this, "Turn on Location to measure speed. Airplane mode is fine — GPS still works.",
                14f, Ui.MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
            c.addView(Ui.button(this, "Open Location settings", Ui.WARN) {
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
            })
            content.addView(c)
        }

        if (!TripTracker.serviceRunning) TripService.start(this)

        val mode = prefs.mode
        val unit = prefs.unit

        val card = Ui.card(this)
        card.addView(Ui.chips(this, Mode.values().map { "${it.emoji} ${it.label}" }, mode.ordinal) { i ->
            val m = Mode.values()[i]
            prefs.mode = m; prefs.unit = m.defaultUnit
            showTab(0)
        })

        val gauge = GaugeView(this).apply {
            max = unit.niceMax(mode.gaugeMaxMs)
            unitText = unit.label
        }
        card.addView(gauge, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12) })

        val status = Ui.text(this, "", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        card.addView(status)
        val place = Ui.text(this, "", 13f, Ui.ACCENT2).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }
        card.addView(place)

        card.addView(Ui.chips(this, SpeedUnit.values().map { it.label }, unit.ordinal) { i ->
            prefs.unit = SpeedUnit.values()[i]
            showTab(0)
        }.apply { setPadding(0, dp(14), 0, 0) })
        content.addView(card)

        // stats
        val statsCard = Ui.card(this)
        val (tMax, vMax) = Ui.stat(this, "Top speed")
        val (tAvg, vAvg) = Ui.stat(this, "Average")
        val (tDist, vDist) = Ui.stat(this, "Distance")
        val (tTime, vTime) = Ui.stat(this, "Trip time")
        val (tAlt, vAlt) = Ui.stat(this, "Altitude")
        val (tHead, vHead) = Ui.stat(this, "Heading")
        statsCard.addView(Ui.grid(this, listOf(tMax, tAvg, tDist, tTime, tAlt, tHead), 2))

        val btns = Ui.row(this).apply { setPadding(0, dp(12), 0, 0) }
        val pauseBtn = Ui.button(this, "", Ui.ACCENT2) {
            TripTracker.setPausedState(!TripTracker.paused); showTab(0)
        }
        pauseBtn.text = if (TripTracker.paused) "Resume" else "Pause"
        btns.addView(pauseBtn, Ui.lp(0, Ui.WRAP, 1f))
        btns.addView(Ui.button(this, "New trip", Ui.ACCENT) {
            AlertDialog.Builder(this).setTitle("Start a new trip?")
                .setMessage("Top speed, distance and time go back to zero.")
                .setPositiveButton("New trip") { _, _ -> TripTracker.reset() }
                .setNegativeButton("Cancel", null).show()
        }, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = dp(8) })
        statsCard.addView(btns)
        statsCard.addView(Ui.button(this, "Stop GPS (saves battery)", Ui.SOFT) {
            TripService.stop(this)
            toastLong("GPS stopped. Open this tab again to restart.")
        }.apply {
            setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) }
        })
        content.addView(statsCard)

        val tip = when (mode) {
            Mode.ROAD -> "Works offline — GPS needs no internet or mobile signal. Keeps running with the screen off; " +
                "the live speed stays in your notification bar."
            Mode.SEA -> "At sea: speed in knots and distance in nautical miles (nmi). No mobile signal needed. " +
                "Keep the phone on deck or near a window; GPS is weak below deck."
            Mode.FLIGHT -> "On a flight: turn on Airplane mode but keep Location ON, and hold the phone near the window. " +
                "First fix can take 1–5 minutes. Altitude is shown in feet."
        }
        content.addView(Ui.card(this).apply { addView(Ui.text(this@MainActivity, "💡  $tip", 13f, Ui.MUTED)) })

        startTicker(500) {
            val u = prefs.unit
            val lost = TripTracker.signalLost()
            val v = if (lost) 0.0 else u.speed(TripTracker.currentMs)
            gauge.value = v
            gauge.bigText = if (lost && !TripTracker.hasAnyFix) "--" else Fmt.speed(v)
            gauge.color = if (lost) Ui.MUTED else Ui.ACCENT
            gauge.subText = when {
                !TripTracker.serviceRunning -> "GPS stopped"
                TripTracker.paused -> "Paused"
                lost && TripTracker.hasAnyFix -> "GPS signal lost"
                lost -> "Searching for satellites…"
                else -> ""
            }
            val sats = if (TripTracker.satsInView > 0) "🛰 ${TripTracker.satsUsed}/${TripTracker.satsInView}" else "🛰 –"
            val acc = TripTracker.accuracyM?.let { "±${it.toInt()} m" } ?: "±– m"
            val net = if (NetInfo.isOnline(this)) "online" else "offline ✓"
            status.text = "$sats  ·  $acc  ·  GPS $net"
            place.text = TripTracker.place?.let { "📍 $it" } ?: ""
            place.visibility = if (TripTracker.place == null) View.GONE else View.VISIBLE

            vMax.text = "${Fmt.speed(u.speed(TripTracker.maxMs))} ${u.label}"
            vAvg.text = "${Fmt.speed(u.speed(TripTracker.averageMs()))} ${u.label}"
            vDist.text = Fmt.dist(TripTracker.distanceM, u)
            vTime.text = Fmt.duration(TripTracker.tripMillis())
            vAlt.text = Fmt.altitude(TripTracker.altitudeM, u)
            vHead.text = if (TripTracker.currentMs > 1) Fmt.heading(TripTracker.bearing) else "–"
        }
    }

    // ======================================================================
    // INTERNET SPEED
    // ======================================================================

    private var test: SpeedTest? = null
    private var testRunning = false

    // test UI (kept so the background test thread can update them)
    private var tGauge: GaugeView? = null
    private var tPhase: TextView? = null
    private var tPing: TextView? = null
    private var tJitter: TextView? = null
    private var tDown: TextView? = null
    private var tUp: TextView? = null
    private var tIsp: TextView? = null
    private var tBtn: TextView? = null

    private fun buildInternet() {
        // --- connection ---
        val conn = Ui.card(this)
        conn.addView(Ui.text(this, "Your connection", 16f, Ui.TEXT, bold = true))
        val kStatus = Ui.kv(this, conn, "Status")
        val kType = Ui.kv(this, conn, "Connected via")
        val kName = Ui.kv(this, conn, "Network")
        val kTech = Ui.kv(this, conn, "Type")
        val kSig = Ui.kv(this, conn, "Signal")
        val kLink = Ui.kv(this, conn, "Wi-Fi link rate")
        val kEst = Ui.kv(this, conn, "Phone's estimate")
        val kFlags = Ui.kv(this, conn, "Data")
        conn.addView(Ui.section(this, "LIVE DATA USE (all apps)"))
        val live = Ui.text(this, "↓ –   ↑ –", 20f, Ui.ACCENT2, bold = true)
        conn.addView(live)
        content.addView(conn)

        // --- speed test ---
        val tc = Ui.card(this)
        tc.addView(Ui.text(this, "Speed test", 16f, Ui.TEXT, bold = true))
        val g = GaugeView(this).apply { max = 100; unitText = "Mbps"; bigText = "0"; color = Ui.ACCENT2 }
        tc.addView(g, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        val phase = Ui.text(this, "Tap Start to test download, upload and ping", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
        }
        tc.addView(phase)
        val (a, vPing) = Ui.stat(this, "Ping")
        val (b, vJit) = Ui.stat(this, "Jitter")
        val (c, vDown) = Ui.stat(this, "Download")
        val (d, vUp) = Ui.stat(this, "Upload")
        tc.addView(Ui.grid(this, listOf(c, d, a, b), 2))
        val isp = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }
        tc.addView(isp)
        val btn = Ui.button(this, if (testRunning) "Cancel" else "Start test", if (testRunning) Ui.BAD else Ui.ACCENT) {
            if (testRunning) cancelTest() else startTest()
        }
        tc.addView(btn, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12) })
        content.addView(tc)
        tGauge = g; tPhase = phase; tPing = vPing; tJitter = vJit; tDown = vDown; tUp = vUp; tIsp = isp; tBtn = btn

        // --- history ---
        val hist = prefs.history()
        if (hist.isNotEmpty()) {
            val hc = Ui.card(this)
            val head = Ui.row(this)
            head.addView(Ui.text(this, "Past tests", 16f, Ui.TEXT, bold = true), Ui.lp(0, Ui.WRAP, 1f))
            head.addView(Ui.text(this, "Clear", 14f, Ui.ACCENT2, bold = true).apply {
                setOnClickListener { prefs.clearHistory(); showTab(1) }
            })
            hc.addView(head)
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            hist.forEach { h ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
                r.addView(Ui.text(this, "↓ ${Fmt.mbps(h.optDouble("down"))}  ↑ ${Fmt.mbps(h.optDouble("up"))} Mbps" +
                    "   ·   ${h.optDouble("ping").toInt()} ms", 15f, Ui.TEXT, bold = true))
                r.addView(Ui.text(this, "${fmt.format(Date(h.optLong("at")))} · ${h.optString("net")}", 12f, Ui.MUTED))
                hc.addView(r)
            }
            content.addView(hc)
        }

        var lastBytes = NetInfo.totalBytes()
        var lastT = SystemClock.elapsedRealtime()
        var n = 0
        startTicker(1000) {
            if (n++ % 2 == 0) {
                val ci = NetInfo.read(this)
                kStatus.text = if (ci.online) "Online ✓" else if (ci.kind == "Offline") "Offline" else "Connected, no internet"
                kStatus.setTextColor(if (ci.online) Ui.ACCENT else Ui.WARN)
                kType.text = ci.kind
                kName.text = ci.name ?: "–"
                kTech.text = ci.tech ?: "–"
                kSig.text = ci.signal ?: "–"
                kLink.text = ci.linkSpeed ?: "–"
                kEst.text = ci.estimate ?: "–"
                kFlags.text = listOfNotNull(if (ci.metered) "Metered" else "Unmetered", if (ci.vpn) "VPN on" else null)
                    .joinToString(" · ")
            }
            val now = SystemClock.elapsedRealtime()
            val bytes = NetInfo.totalBytes()
            val secs = (now - lastT) / 1000.0
            if (secs > 0 && bytes.first >= 0) {
                val down = (bytes.first - lastBytes.first) * 8 / secs / 1e6
                val up = (bytes.second - lastBytes.second) * 8 / secs / 1e6
                live.text = "↓ ${rate(down)}   ↑ ${rate(up)}"
            }
            lastBytes = bytes; lastT = now
        }
    }

    private fun rate(mbps: Double): String =
        if (mbps < 1) "${(mbps * 1000).toInt()} kbps" else "${Fmt.mbps(mbps)} Mbps"

    private fun startTest() {
        val ci = NetInfo.read(this)
        if (ci.kind == "Offline") { toastLong("You're offline. Connect to Wi-Fi or mobile data first."); return }
        if (ci.kind.startsWith("Mobile")) {
            AlertDialog.Builder(this).setTitle("Uses mobile data")
                .setMessage("A test can use 50–300 MB on fast 4G/5G. Continue?")
                .setPositiveButton("Start") { _, _ -> runTest(ci) }
                .setNegativeButton("Cancel", null).show()
        } else runTest(ci)
    }

    private fun runTest(ci: Connection) {
        testRunning = true
        tBtn?.text = "Cancel"; tBtn?.background = Ui.rounded(this, Ui.BAD, 14)
        listOf(tPing, tJitter, tDown, tUp).forEach { it?.text = "–" }
        tIsp?.text = ""
        tGauge?.apply { max = 100; value = 0.0; bigText = "0" }
        val netLabel = listOfNotNull(ci.kind, ci.tech?.takeIf { !it.startsWith("allow") }, ci.name?.takeIf { !it.startsWith("(") })
            .joinToString(" · ")

        val st = SpeedTest(object : SpeedTest.Listener {
            override fun onPhase(phase: SpeedTest.Phase) = ui {
                tPhase?.text = when (phase) {
                    SpeedTest.Phase.PING -> "Measuring ping…"
                    SpeedTest.Phase.DOWNLOAD -> "Testing download…"
                    SpeedTest.Phase.UPLOAD -> "Testing upload…"
                    SpeedTest.Phase.DONE -> "Done"
                }
                tGauge?.color = if (phase == SpeedTest.Phase.UPLOAD) Ui.ACCENT else Ui.ACCENT2
                tGauge?.subText = if (phase == SpeedTest.Phase.UPLOAD) "UPLOAD" else if (phase == SpeedTest.Phase.DOWNLOAD) "DOWNLOAD" else ""
            }

            override fun onLive(phase: SpeedTest.Phase, mbps: Double, progress: Float) = ui {
                if (phase == SpeedTest.Phase.PING) return@ui
                tGauge?.apply {
                    max = gaugeMax(mbps)
                    value = mbps; bigText = Fmt.mbps(mbps).let { if (it == "–") "0" else it }
                }
                (if (phase == SpeedTest.Phase.DOWNLOAD) tDown else tUp)?.text = "${Fmt.mbps(mbps)} Mbps"
            }

            override fun onPing(pingMs: Double, jitterMs: Double) = ui {
                tPing?.text = "${pingMs.toInt()} ms"; tJitter?.text = "${jitterMs.toInt()} ms"
            }

            override fun onResult(r: SpeedTest.Result) = ui {
                testRunning = false
                prefs.addHistory(JSONObject().apply {
                    put("at", System.currentTimeMillis()); put("down", r.downMbps); put("up", r.upMbps)
                    put("ping", r.pingMs); put("net", netLabel)
                })
                if (prefs.tab != 1) return@ui
                showTab(1)   // rebuilds the screen with the new history; then fill in the results
                tPhase?.text = verdict(r.downMbps)
                tPing?.text = "${r.pingMs.toInt()} ms"; tJitter?.text = "${r.jitterMs.toInt()} ms"
                tDown?.text = "${Fmt.mbps(r.downMbps)} Mbps"; tUp?.text = "${Fmt.mbps(r.upMbps)} Mbps"
                tIsp?.text = listOfNotNull(r.isp?.let { "ISP: $it" }, r.server?.let { "Server: $it" },
                    "Data used: ${r.bytesUsed / (1024 * 1024)} MB").joinToString("  ·  ")
                tGauge?.apply {
                    max = gaugeMax(r.downMbps)
                    value = r.downMbps; bigText = Fmt.mbps(r.downMbps); subText = "DOWNLOAD"; color = Ui.ACCENT2
                }
            }

            override fun onError(msg: String) = ui {
                testRunning = false
                tPhase?.text = "Test failed: $msg"
                tBtn?.text = "Start test"; tBtn?.background = Ui.rounded(this@MainActivity, Ui.ACCENT, 14)
            }
        })
        test = st
        Thread { st.run() }.start()
    }

    private fun gaugeMax(mbps: Double) =
        listOf(10, 50, 100, 250, 500, 1000, 2500, 10000).first { it >= mbps * 1.05 || it == 10000 }

    private fun cancelTest() {
        test?.cancel()
        testRunning = false
        tPhase?.text = "Cancelled"
        tBtn?.text = "Start test"; tBtn?.background = Ui.rounded(this, Ui.ACCENT, 14)
    }

    private fun verdict(down: Double) = when {
        down >= 100 -> "Excellent — 4K streaming, big downloads, many devices"
        down >= 25 -> "Very good — HD/4K video and video calls"
        down >= 8 -> "Good — HD video and calls"
        down >= 2 -> "OK — browsing, WhatsApp, SD video"
        else -> "Slow — messages and light browsing"
    }

    /** Runs on the UI thread, ignoring the call if the test was cancelled. */
    private fun ui(f: () -> Unit) {
        main.post { if (test?.isCancelled != true) f() }
    }

    // ======================================================================

    private fun askPermissions() {
        val need = mutableListOf<String>()
        fun want(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        want(Manifest.permission.ACCESS_FINE_LOCATION)
        want(Manifest.permission.ACCESS_COARSE_LOCATION)
        want(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) want(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showTab(prefs.tab)
    }

    private fun toastLong(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
