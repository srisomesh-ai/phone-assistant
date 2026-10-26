package com.srisomesh.assistant

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small pop-up shown when you say the wake word — appears over the lock screen so you
 * can see what was heard and the reply. Taps anywhere to dismiss. Auto-closes when idle.
 */
class ResponseActivity : Activity() {

    companion object {
        const val EXTRA_UNLOCK = "unlock"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var textView: TextView
    private var closer: Runnable? = null

    private val busListener: (String, String) -> Unit = { status, text -> render(status, text) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2000000"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { ListenService.instance?.cancelTurn(); finish() }
        }
        val name = TextView(this).apply {
            text = "Hey ${Prefs(this@ResponseActivity).wakeName}"
            setTextColor(Color.parseColor("#85B7EB"))
            textSize = 16f
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
        }
        textView = TextView(this).apply {
            setTextColor(Color.parseColor("#E8E8E8"))
            textSize = 17f
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "Tap to dismiss"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(name)
        root.addView(statusView)
        root.addView(textView)
        root.addView(hint)
        setContentView(root)

        render(UiBus.status, UiBus.text)
        UiBus.add(busListener)
    }

    override fun onResume() {
        super.onResume()
        UiBus.add(busListener)
        render(UiBus.status, UiBus.text)
    }

    override fun onPause() {
        super.onPause()
        UiBus.remove(busListener)
    }

    override fun onDestroy() {
        UiBus.remove(busListener)
        super.onDestroy()
    }

    private fun render(status: String, text: String) {
        statusView.text = status
        textView.text = text
        // auto-close a little after it goes back to Ready / Stopped
        closer?.let { main.removeCallbacks(it) }
        if (status.startsWith("Ready") || status.startsWith("Stopped") || status.startsWith("Didn't")) {
            val r = Runnable { finish() }
            closer = r
            main.postDelayed(r, 6000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
