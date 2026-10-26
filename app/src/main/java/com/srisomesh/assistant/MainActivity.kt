package com.srisomesh.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main screen — matches the approved sample: header, listening circle, wake name + unlock tiles,
 * one-time setup checklist, recent commands.
 */
class MainActivity : android.app.Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var prefs: Prefs

    private val busListener: (String, String) -> Unit = { _, _ -> main.post { rebuild() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16); setPadding(p, p, p, p)
        }
        scroll.addView(root)
        setContentView(scroll)
        requestCorePermissions()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        UiBus.add(busListener)
    }

    override fun onPause() {
        super.onPause()
        UiBus.remove(busListener)
    }

    // ---------------- build the screen ----------------

    private fun rebuild() {
        root.removeAllViews()
        val cardWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Ui.LINE)
            }
            val p = dp(16); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // header: name + gear
        val header = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "Somesh Assistant", 18f, Ui.TEXT, bold = true),
            Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gear = Ui.text(this, "⚙", 22f, Ui.MUTED).apply {
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        header.addView(gear)
        cardWrap.addView(header)

        // listening circle
        val running = ListenService.isRunning
        val modelReady = WakeEngine.isDownloaded(this)
        val circle = TextView(this).apply {
            text = if (running) "👂" else "●"
            textSize = 30f
            gravity = Gravity.CENTER
            val size = dp(84)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (running) Ui.OK_BG else Ui.SOFT)
            }
        }
        cardWrap.addView(circle)

        val statusLine = if (!modelReady) "Finish setup below to start"
        else if (running) "Listening for \"Hey ${prefs.wakeName}\"" else "Paused — tap Start listening"
        cardWrap.addView(Ui.text(this, statusLine, 15f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        cardWrap.addView(Ui.text(this, "Works with screen off · on charge", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        })

        // live "heard" line — lets you confirm the mic/model is working and see what it hears
        if (running && UiBus.lastHeard.isNotBlank()) {
            cardWrap.addView(Ui.text(this, "Heard: “${UiBus.lastHeard}”", 12f, Ui.ACCENT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }

        // start / stop button
        val toggle = Button(this).apply {
            text = if (running) "Stop listening" else "Start listening"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            }
            setOnClickListener {
                if (running) ListenService.stop(this@MainActivity) else startListening()
                main.postDelayed({ rebuild() }, 600)
            }
        }
        cardWrap.addView(toggle)

        // two tiles: wake name, unlock
        val tiles = Ui.row(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        tiles.addView(tile("Wake name", "Hey ${prefs.wakeName}") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        tiles.addView(tile("Unlock", "Face / PIN") {
            info("Unlock",
                "When a command needs the phone open, it wakes the screen and you unlock with face, fingerprint or PIN. " +
                    "For table phones, turn on Smart Lock → Trusted places in Vivo settings so they stay unlocked at your office.")
        }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        cardWrap.addView(tiles)

        // one-time setup checklist
        cardWrap.addView(Ui.text(this, "One-time setup", 13f, Ui.MUTED).apply {
            setPadding(0, dp(16), 0, dp(6))
        })
        cardWrap.addView(checkRow("Microphone", hasMic()) { requestCorePermissions() })
        cardWrap.addView(checkRow("Wake word model", modelReady) { downloadModel() })
        cardWrap.addView(checkRow("Pop-up over lock screen", canOverlay()) { askOverlay() })
        cardWrap.addView(checkRow("Battery not restricted", batteryOk()) { askBattery() })
        cardWrap.addView(checkRow("Read notifications (optional)", notifOk()) { openNotifAccess() })
        cardWrap.addView(checkRow("WhatsApp auto-send (optional)", ControlService.instance != null) { openAccessibility() })
        if (isVivo() && !prefs.vivoSetupDone) {
            cardWrap.addView(checkRow("Vivo: allow background + auto-start", false) { vivoHelp() })
        }

        // recent
        cardWrap.addView(Ui.text(this, "Recent", 13f, Ui.MUTED).apply {
            setPadding(0, dp(16), 0, dp(6))
        })
        val recent = prefs.recent()
        if (recent.isEmpty()) {
            cardWrap.addView(Ui.text(this, "Nothing yet. Say \"Hey ${prefs.wakeName}\" then a command.", 13f, Ui.MUTED))
        } else {
            recent.forEach { (heard, result) ->
                val r = Ui.row(this).apply {
                    setPadding(0, dp(7), 0, dp(7))
                    background = GradientDrawable().apply { setStroke(0, 0); }
                }
                r.addView(Ui.text(this, "“$heard”", 13f, Ui.TEXT),
                    Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(Ui.text(this, result, 13f, Ui.MUTED))
                cardWrap.addView(divider())
                cardWrap.addView(r)
            }
        }

        root.addView(cardWrap)
    }

    private fun tile(label: String, value: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.SOFT); cornerRadius = dp(8).toFloat() }
            val p = dp(10); setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
        box.addView(Ui.text(this, label, 12f, Ui.MUTED))
        box.addView(Ui.text(this, value, 14f, Ui.TEXT, bold = true).apply { setPadding(0, dp(2), 0, 0) })
        return box
    }

    private fun checkRow(label: String, done: Boolean, fix: () -> Unit): View {
        val r = Ui.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val mark = Ui.text(this, if (done) "✓" else "⚠", 16f, if (done) Ui.OK else Ui.WARN)
        mark.setPadding(0, 0, dp(10), 0)
        r.addView(mark)
        r.addView(Ui.text(this, label, 14f, if (done) Ui.TEXT else Ui.WARN),
            Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!done) {
            r.addView(Ui.text(this, "Fix", 14f, Ui.ACCENT, bold = true).apply {
                setOnClickListener { fix() }
            })
        }
        return r
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Ui.LINE)
    }

    // ---------------- actions / checks ----------------

    private fun startListening() {
        if (!hasMic()) { requestCorePermissions(); return }
        if (!WakeEngine.isDownloaded(this)) { downloadModel(); return }
        ListenService.start(this)
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun canOverlay() = Settings.canDrawOverlays(this)

    private fun batteryOk(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun notifOk(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun isVivo() = Build.MANUFACTURER.equals("vivo", true) || Build.BRAND.equals("vivo", true)

    private fun requestCorePermissions() {
        val need = mutableListOf<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_CONTACTS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CALL_PHONE)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rebuild()
    }

    private fun askOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) { toast("Open Settings → Apps → Somesh Assistant → Display over other apps") }
    }

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { toast("Open Settings → Battery and allow unrestricted") }
    }

    private fun openNotifAccess() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        catch (e: Exception) { toast("Open Settings → Notification access") }
    }

    private fun openAccessibility() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (e: Exception) {}
        info("WhatsApp auto-send",
            "Turn on \"Somesh Assistant control\" in Accessibility. It is only used to press WhatsApp's send button for messages you dictate, and to lock the phone when you ask.")
    }

    private fun downloadModel() {
        if (WakeEngine.isDownloaded(this)) { rebuild(); return }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading wake word model")
            .setMessage("About 40 MB, one time. Needs internet now; works offline after.")
            .setCancelable(false)
            .create()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; val p = dp(24); setPadding(p, p, p, p)
        }
        dialog.setView(bar)
        dialog.show()
        Thread {
            val err = WakeEngine.download(this) { pct -> main.post { bar.progress = pct } }
            main.post {
                dialog.dismiss()
                if (err == null) { toast("Wake word ready"); startListening() }
                else AlertDialog.Builder(this)
                    .setTitle("Download failed")
                    .setMessage("$err\n\nCheck internet and try again.")
                    .setPositiveButton("OK", null).show()
                rebuild()
            }
        }.start()
    }

    private fun vivoHelp() {
        AlertDialog.Builder(this)
            .setTitle("Vivo: keep the assistant alive")
            .setMessage(
                "Vivo closes background apps. Do these once:\n\n" +
                    "1. Settings → Battery → High background power consumption → allow Somesh Assistant.\n" +
                    "2. Settings → Apps → Somesh Assistant → Auto-start: ON.\n" +
                    "3. In Recent apps, swipe down on Somesh Assistant and tap the lock, so it isn't cleared.\n\n" +
                    "Then this phone keeps listening."
            )
            .setPositiveButton("Done") { _, _ -> prefs.vivoSetupDone = true; rebuild() }
            .setNeutralButton("Open settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
            .show()
    }

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
