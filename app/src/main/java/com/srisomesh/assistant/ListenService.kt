package com.srisomesh.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Always-on listener. Runs as a foreground service so it keeps hearing the wake phrase
 * with the screen off. Flow: wake phrase → beep → hear one command → act / reply aloud → listen again.
 */
class ListenService : Service(), RecognitionListener {

    companion object {
        const val CHANNEL = "listening"
        const val CHANNEL_ALERT = "assistant_alert"
        const val NOTIF_ID = 7

        @Volatile
        var instance: ListenService? = null
            private set

        val isRunning get() = instance != null

        fun start(ctx: Context) {
            Prefs(ctx).listeningWanted = true
            val i = Intent(ctx, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            Prefs(ctx).listeningWanted = false
            ctx.stopService(Intent(ctx, ListenService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var commandListener: CommandListener? = null
    private var paused = false
    private var busy = false
    lateinit var router: CommandRouter
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        val n = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        Speaker.init(this)
        router = CommandRouter(this)
        Thread {
            val model = WakeEngine.load(this)
            main.post {
                if (model == null) {
                    setStatus("Wake model not downloaded — open the app")
                } else startWake()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopWake()
        commandListener?.cleanup()
        instance = null
        UiBus.post("Stopped")
        super.onDestroy()
    }

    // ---------------- wake phrase ----------------

    private fun startWake() {
        if (paused || busy || speech != null) return
        val model = WakeEngine.model ?: return
        try {
            val rec = Recognizer(model, 16000f)
            recognizer = rec
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            setStatus("Listening for \"Hey ${Prefs(this).wakeName}\"")
        } catch (e: Exception) {
            setStatus("Mic busy — retrying")
            main.postDelayed({ startWake() }, 3000)
        }
    }

    private fun stopWake() {
        try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
        speech = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
    }

    /** Let another screen (e.g. "Teach wake word") use the mic for a while. */
    fun pause() { paused = true; stopWake() }
    fun resume() { paused = false; startWake() }

    override fun onPartialResult(hypothesis: String?) = checkWake(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = checkWake(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = checkWake(hypothesis, "text")
    override fun onError(exception: Exception?) {
        stopWake()
        main.postDelayed({ startWake() }, 2000)
    }
    override fun onTimeout() {
        stopWake(); startWake()
    }

    private fun checkWake(json: String?, key: String) {
        if (busy) return
        val heard = try { JSONObject(json ?: "{}").optString(key, "") } catch (e: Exception) { "" }
        if (heard.isBlank()) return
        // show what the mic/model is hearing — live, including partial words — for debugging
        UiBus.postHeard(heard)
        if (WakeMatcher.matches(this, heard)) {
            // hop to the main thread: stopping the recogniser from inside its own
            // callback thread can hang, which looked like "no response".
            main.post { if (!busy) onWake() }
        }
    }

    // ---------------- one conversation turn ----------------

    private fun onWake() {
        busy = true
        stopWake()
        wakeScreenBriefly()
        UiBus.post("Listening…", "")
        showResponseScreen()
        Speaker.beep()
        main.postDelayed({
            val cl = CommandListener(this)
            commandListener = cl
            cl.listen(Prefs(this).commandLang) { text -> onCommand(text) }
        }, 250)
    }

    private fun onCommand(text: String?) {
        commandListener = null
        if (text.isNullOrBlank()) {
            UiBus.post("Didn't catch that", "")
            Speaker.say("Sorry, I didn't catch that.") { endTurn() }
            return
        }
        UiBus.post("Thinking…", text)
        router.handle(text) { reply ->
            Prefs(this).addRecent(text, reply.ifBlank { "done" })
            UiBus.post("Ready", "$text\n\n$reply")
            if (reply.isBlank()) endTurn() else Speaker.say(reply) { endTurn() }
        }
    }

    private fun endTurn() {
        busy = false
        startWake()
    }

    /** Called when the user taps the screen / a stop command. */
    fun cancelTurn() {
        Speaker.stop()
        commandListener?.cleanup()
        commandListener = null
        if (busy) endTurn()
    }

    // ---------------- screen & notification ----------------

    private fun wakeScreenBriefly() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "assistant:wake"
            )
            wl.acquire(10_000)
        } catch (_: Exception) {}
    }

    fun showResponseScreen(unlock: Boolean = false) {
        val i = Intent(this, ResponseActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(ResponseActivity.EXTRA_UNLOCK, unlock)
        }
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        // Fallback: full-screen notification (also shows over the lock screen)
        val pi = PendingIntent.getActivity(this, if (unlock) 2 else 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey ${Prefs(this).wakeName}")
            .setContentText(if (unlock) "Unlocking…" else "Listening…")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(8, n)
    }

    private fun setStatus(s: String) {
        UiBus.post(s)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Assistant pop-up", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Somesh Assistant")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
