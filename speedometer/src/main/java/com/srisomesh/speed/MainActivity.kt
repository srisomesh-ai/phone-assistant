package com.srisomesh.speed

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
        showTab(prefs.tab.coerceIn(0, 2))
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
        tabRow = Ui.chips(this, listOf("🚗  Travel", "📶  Internet", "📡  Devices"), i) { showTab(it) }
        tabHolder.addView(tabRow)
        stopTicker()
        content.removeAllViews()
        when (i) {
            0 -> { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildTravel() }
            1 -> { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildInternet() }
            else -> { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildDevices() }
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
    // DEVICES ON NETWORK (LAN scan)
    // ======================================================================

    private var scanner: LanScanner? = null
    private var scanning = false
    private val deviceViews = LinkedHashMap<String, LinearLayout>()
    private var devInfo: TextView? = null
    private var devCount: TextView? = null
    private var devProgress: TextView? = null
    private var devList: LinearLayout? = null
    private var devBtn: TextView? = null
    private var devNote: TextView? = null

    private fun buildDevices() {
        val online = NetInfo.read(this)
        val head = Ui.card(this)
        head.addView(Ui.text(this, "Devices on this network", 16f, Ui.TEXT, bold = true))
        devInfo = Ui.text(this, if (online.kind.startsWith("Wi-Fi")) "Wi-Fi: ${online.name ?: "–"}" else
            "Best on Wi-Fi. You're on ${online.kind}.", 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        head.addView(devInfo)
        kMy = Ui.kv(this, head, "Your phone")
        kGw = Ui.kv(this, head, "Router / gateway")
        kNet = Ui.kv(this, head, "Scanning range")
        devCount = Ui.text(this, "", 22f, Ui.ACCENT, bold = true).apply { setPadding(0, dp(10), 0, 0) }
        head.addView(devCount)
        devProgress = Ui.text(this, "", 12f, Ui.MUTED)
        head.addView(devProgress)
        devBtn = Ui.button(this, if (scanning) "Stop" else "Scan network", if (scanning) Ui.BAD else Ui.ACCENT) {
            if (scanning) stopScan() else startScan()
        }
        head.addView(devBtn, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12) })
        content.addView(head)

        val listCard = Ui.card(this)
        listCard.addView(Ui.text(this, "Found devices", 15f, Ui.TEXT, bold = true))
        devList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(devList)
        content.addView(listCard)
        deviceViews.clear()

        devNote = Ui.text(this, DEVICES_HELP, 13f, Ui.MUTED)
        content.addView(Ui.card(this).apply { addView(devNote) })
    }

    private var kMy: TextView? = null
    private var kGw: TextView? = null
    private var kNet: TextView? = null

    private fun startScan() {
        if (NetInfo.read(this).kind == "Offline") { toastLong("Connect to Wi-Fi first."); return }
        scanning = true
        devBtn?.text = "Stop"; devBtn?.background = Ui.rounded(this, Ui.BAD, 14)
        devCount?.text = "Scanning…"
        devList?.removeAllViews(); deviceViews.clear(); lastDevices.clear()
        val sc = LanScanner(this)
        scanner = sc
        Thread {
            sc.scan(object : LanScanner.Listener {
                override fun onInfo(ssid: String?, selfIp: String?, gatewayIp: String?, subnet: String?, hostCount: Int) = postU {
                    if (prefs.tab != 2) return@postU
                    kMy?.text = selfIp ?: "–"
                    kGw?.text = gatewayIp ?: "–"
                    kNet?.text = "${subnet ?: "–"}  ($hostCount hosts)"
                }
                override fun onProgress(done: Int, total: Int) = postU {
                    if (prefs.tab == 2 && scanning) devProgress?.text = "Checked $done of $total addresses…"
                }
                override fun onFound(device: LanScanner.Device) = postU {
                    lastDevices[device.ip] = device
                    if (prefs.tab != 2) return@postU
                    renderDevice(device)
                    devCount?.text = "${deviceViews.size} device${if (deviceViews.size == 1) "" else "s"} found"
                }
                override fun onDone(devices: List<LanScanner.Device>, isolated: Boolean) = postU {
                    scanning = false
                    if (prefs.tab != 2) return@postU
                    devProgress?.text = "Scan complete"
                    devBtn?.text = "Scan again"; devBtn?.background = Ui.rounded(this@MainActivity, Ui.ACCENT, 14)
                    devCount?.text = "${devices.size} device${if (devices.size == 1) "" else "s"} responding"
                    devNote?.text = if (isolated)
                        "Only your phone and the router answered. This Wi-Fi is almost certainly using " +
                        "client isolation (common on company Wi-Fi with individual logins), which stops devices " +
                        "from seeing each other. No app can get past that — ask your network admin for the device " +
                        "list.\n\n" + DEVICES_HELP
                    else DEVICES_HELP
                }
                override fun onError(msg: String) = postU {
                    scanning = false
                    devProgress?.text = "Couldn't scan: $msg"
                    devBtn?.text = "Scan network"; devBtn?.background = Ui.rounded(this@MainActivity, Ui.ACCENT, 14)
                }
            })
        }.start()
    }

    /** Tap a device → probe what's actually observable from here: live ping + which services it runs. */
    private fun openDevice(ip: String) {
        val d = deviceSnapshot(ip) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18); setPadding(p, p, p, p)
        }
        fun line(t: String, size: Float, color: Int = Ui.TEXT, bold: Boolean = false) =
            Ui.text(this, t, size, color, bold).also { box.addView(it.apply { setPadding(0, dp(3), 0, dp(3)) }) }

        val heading = when {
            d.isSelf -> "📱 This phone"
            d.isGateway -> "📶 Router / gateway"
            else -> d.name ?: (d.vendor?.let { "$it device" } ?: "Device")
        }
        line(heading, 18f, Ui.TEXT, true)
        line("IP: ${d.ip}", 13f, Ui.MUTED)
        d.mac?.let { line("MAC: $it", 13f, Ui.MUTED) }
        d.vendor?.let { line("Maker: $it", 13f, Ui.MUTED) }

        val pingTitle = line("Ping", 14f, Ui.ACCENT2, true).apply { setPadding(0, dp(12), 0, dp(2)) }
        val pingBody = line("testing…", 13f, Ui.MUTED)
        val svcTitle = line("Services running", 14f, Ui.ACCENT2, true).apply { setPadding(0, dp(12), 0, dp(2)) }
        val svcBody = line("scanning ports…", 13f, Ui.TEXT)

        val openWeb = Ui.button(this, "Open web page", Ui.ACCENT) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${d.ip}"))) } catch (_: Exception) {}
        }.apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12) } }
        box.addView(openWeb)

        val copyBtn = Ui.button(this, "Copy IP", Ui.SOFT) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("ip", d.ip)); toastLong("Copied ${d.ip}")
        }.apply { setTextColor(Ui.TEXT); layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) } }
        box.addView(copyBtn)

        box.addView(Ui.text(this, "This shows how the device looks on the Wi-Fi and what it runs — not whether " +
            "it has internet or is logged in. That isn't visible from your phone.", 12f, Ui.MUTED).apply {
            setPadding(0, dp(12), 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(box); setBackgroundColor(Ui.CARD) }
        val dlg = AlertDialog.Builder(this).setView(scroll)
            .setPositiveButton("Close", null)
            .setOnDismissListener { DeviceProbe.cancelScan = true }.create()
        dlg.show()

        DeviceProbe.cancelScan = false
        Thread {
            val r = DeviceProbe.ping(d.ip, 5) { _, _, _ -> }
            main.post {
                pingTitle.text = "Ping"
                pingBody.text = if (r.received == 0) "No reply (device may block ping, or just left)"
                else "${r.received}/${r.sent} replies · ${r.avgMs} ms avg (min ${r.minMs}, max ${r.maxMs})" +
                    if (r.lossPct > 0) " · ${r.lossPct}% loss" else ""
            }
        }.start()
        Thread {
            val open = DeviceProbe.identify(d.ip,
                onPort = { done, total -> main.post { if (svcBody.text.startsWith("scanning")) svcBody.text = "scanning… $done/$total" } },
                onOpen = { _, _ -> })
            main.post {
                svcBody.text = if (open.isEmpty()) "No open ports found (common for phones and well-secured devices)"
                else open.joinToString("\n") { "• ${it.second}  (port ${it.first})" }
                val web = DeviceProbe.hasWeb(open)
                if (web != null) {
                    openWeb.visibility = View.VISIBLE
                    openWeb.setOnClickListener {
                        val scheme = if (web == 443 || web == 8443) "https" else "http"
                        val port = if (web == 80 || web == 443) "" else ":$web"
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://${d.ip}$port"))) } catch (_: Exception) {}
                    }
                }
            }
        }.start()
    }

    private fun deviceSnapshot(ip: String): LanScanner.Device? = lastDevices[ip]

    private val lastDevices = LinkedHashMap<String, LanScanner.Device>()

    private fun postU(f: () -> Unit) { main.post(f) }

    private fun stopScan() {
        scanner?.cancel(); scanning = false
        devBtn?.text = "Scan network"; devBtn?.background = Ui.rounded(this, Ui.ACCENT, 14)
        devProgress?.text = "Stopped"
    }

    private fun renderDevice(d: LanScanner.Device) {
        val list = devList ?: return
        val row = deviceViews.getOrPut(d.ip) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                background = Ui.rounded(this@MainActivity, Ui.CARD, 8)
                setOnClickListener { openDevice(d.ip) }
            }
            list.addView(divider()); list.addView(r)
            r
        }
        row.removeAllViews()
        val title = when {
            d.isSelf -> "📱 This phone"
            d.isGateway -> "📶 Router / gateway"
            else -> d.name ?: (d.vendor?.let { "$it device" } ?: "Device")
        }
        row.addView(Ui.text(this, title, 15f, if (d.isGateway) Ui.ACCENT2 else Ui.TEXT, bold = true))
        val detail = buildList {
            add(d.ip)
            d.vendor?.let { add(it) }
            d.mac?.let { add(it) }
            if (d.pingMs in 0..100000) add("${d.pingMs} ms")
            d.openHint?.let { add(it) }
        }.joinToString("  ·  ")
        row.addView(Ui.text(this, detail, 12f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(Ui.MATCH, dp(1))
        setBackgroundColor(Ui.LINE)
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

    companion object {
        private const val DEVICES_HELP =
            "This lists devices on your Wi-Fi that answer the phone, with their IP, name and maker where " +
            "available. It cannot show which devices are logged in or which have internet access — that lives " +
            "on the company's Wi-Fi controller, not on the phone. Names and MAC/maker are blank for devices " +
            "that don't share them (Android hides most MACs since Android 10). Turn Location ON for the best results."
    }
}
