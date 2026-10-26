package com.srisomesh.assistant

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Settings behind the gear: wake name, teach wake word, command language, trusted Wi-Fi, AI model. */
class SettingsActivity : android.app.Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout

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
        build()
    }

    private fun build() {
        root.removeAllViews()
        root.addView(Ui.text(this, "Settings", 22f, Ui.TEXT, bold = true).apply { setPadding(0, 0, 0, dp(12)) })

        section("Wake name")
        val nameBox = EditText(this).apply {
            setText(prefs.wakeName); setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        }
        root.addView(nameBox)
        root.addView(hint("The phone answers to \"Hey <name>\". Keep it two syllables, like Bangru or Raja."))
        button("Save name") {
            val v = nameBox.text.toString().trim()
            if (v.isNotEmpty()) { prefs.wakeName = v; toast("Saved"); restartListening() }
        }

        section("Teach wake word")
        root.addView(hint("Say \"Hey ${prefs.wakeName}\" a few times so it learns how you say it. Improves waking, fewer false triggers."))
        val taught = prefs.wakeVariants.size
        root.addView(Ui.text(this, if (taught == 0) "Not taught yet" else "$taught samples learned", 13f, Ui.MUTED))
        button("Teach now") { teachWakeWord() }
        if (taught > 0) button("Clear samples") { prefs.wakeVariants = emptySet(); toast("Cleared"); build() }

        section("Command language")
        root.addView(hint("Language it expects after the wake word. Telugu uses the phone's online recognition; English also works offline."))
        val langRow = Ui.row(this)
        langRow.addView(langButton("English", "en-IN"))
        langRow.addView(langButton("Telugu", "te-IN"))
        root.addView(langRow)

        section("Trusted Wi-Fi (for Smart Lock reminder)")
        root.addView(hint("Add your office/home Wi-Fi names here as a note, then set them as a Trusted place in Vivo's Smart Lock so the phone stays unlocked there."))
        val wifiBox = EditText(this).apply {
            setText(prefs.trustedWifi.joinToString(", ")); setTextColor(Ui.TEXT)
            hint = "Office WiFi, Home WiFi"; setHintTextColor(Ui.MUTED)
        }
        root.addView(wifiBox)
        button("Save Wi-Fi names") {
            prefs.trustedWifi = wifiBox.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            toast("Saved")
        }

        section("On-phone AI model")
        val has = LocalAi.hasModel(this)
        root.addView(Ui.text(this, if (has) "Model added ✓" else "No model yet", 14f, if (has) Ui.OK else Ui.WARN))
        root.addView(hint("Lets the assistant chat offline. Download a Gemma 3 1B .task file to the phone, then pick it here. Without it, simple commands still work; chat needs your home AI."))
        button(if (has) "Change model file" else "Pick model file") { pickModel() }
        button("How to get the model") {
            info("Get the AI model",
                "On the phone's browser, download a file named like \"gemma3-1b-it.task\" from the LiteRT community page on Hugging Face. " +
                    "Then come back here and tap Pick model file. It's a few hundred MB, one time.")
        }

        section("")
        button("Done") { finish() }
    }

    // ---------------- helpers ----------------

    private fun section(title: String) {
        root.addView(Ui.text(this, title, 13f, Ui.MUTED).apply { setPadding(0, dp(18), 0, dp(6)) })
    }

    private fun hint(s: String): TextView =
        Ui.text(this, s, 12f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(6)) }

    private fun button(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setOnClickListener { onClick() }
        })
    }

    private fun langButton(label: String, code: String): Button {
        val selected = prefs.commandLang == code
        return Button(this).apply {
            text = if (selected) "✓ $label" else label
            isAllCaps = false
            layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4); leftMargin = dp(4)
            }
            setOnClickListener { prefs.commandLang = code; build() }
        }
    }

    private fun teachWakeWord() {
        val listener = CommandListener(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Say \"Hey ${prefs.wakeName}\"")
            .setMessage("Listening… say it once, clearly.")
            .setNegativeButton("Cancel") { _, _ -> listener.cleanup() }
            .setCancelable(false)
            .create()
        dialog.show()
        // free the mic from the always-on service while teaching
        ListenService.instance?.pause()
        listener.listen("en-IN") { heard ->
            ListenService.instance?.resume()
            dialog.dismiss()
            if (heard.isNullOrBlank()) { toast("Didn't hear it, try again"); return@listen }
            val set = prefs.wakeVariants.toMutableSet()
            set.add(heard)
            prefs.wakeVariants = set
            toast("Learned: “$heard”")
            build()
        }
    }

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try { startActivityForResult(i, 42) }
        catch (e: Exception) { toast("No file picker found") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryName(uri)
        if (!name.endsWith(".task") && !name.endsWith(".bin") && !name.endsWith(".tflite")) {
            info("Wrong file", "Pick the AI model file (ends with .task). You picked: $name")
            return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Copying model")
            .setMessage("Please wait…").setCancelable(false).create()
        dialog.show()
        Thread {
            val dest = File(filesDir, "ai-model.task")
            val ok = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                true
            } catch (e: Exception) { false }
            main.post {
                dialog.dismiss()
                if (ok) { prefs.aiModelPath = dest.absolutePath; LocalAi.reset(); toast("Model added"); build() }
                else toast("Couldn't copy the file")
            }
        }.start()
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) it.getString(idx) else ""
            } ?: ""
        } catch (e: Exception) { "" }
    }

    private fun restartListening() {
        if (ListenService.isRunning) {
            ListenService.stop(this)
            main.postDelayed({ ListenService.start(this) }, 800)
        }
    }

    private fun info(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
