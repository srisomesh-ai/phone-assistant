package com.srisomesh.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Talks back out loud with the phone's own voice (Telugu or English, picked from the text). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var counter = 0

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = finish(id)
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String?) = finish(id)
                override fun onError(id: String?, code: Int) = finish(id)
            })
        }
    }

    private fun finish(id: String?) {
        val cb = id?.let { callbacks.remove(it) } ?: return
        main.post(cb)
    }

    fun isTelugu(text: String) = text.any { it in 'ఀ'..'౿' }

    /** Speaks the text, then runs [done] on the main thread. */
    fun say(text: String, done: () -> Unit = {}) {
        val t = tts
        if (!ready || t == null || text.isBlank()) {
            main.post(done); return
        }
        val locale = if (isTelugu(text)) Locale("te", "IN") else Locale("en", "IN")
        val res = t.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.ENGLISH)
        }
        val id = "u${counter++}"
        callbacks[id] = done
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun stop() {
        tts?.stop()
        val pending = callbacks.values.toList()
        callbacks.clear()
        pending.forEach { main.post(it) }
    }

    fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {
        }
    }
}
