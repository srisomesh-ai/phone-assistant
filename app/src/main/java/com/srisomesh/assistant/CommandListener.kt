package com.srisomesh.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.SpeechService

/**
 * Hears ONE command after the wake word.
 * First choice: the phone's speech recogniser (Telugu or English, needs internet for Telugu).
 * Fallback: offline Vosk (English only) if the phone's recogniser refuses (e.g. on some lock screens).
 */
class CommandListener(private val ctx: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var vosk: SpeechService? = null
    private var delivered = false

    fun listen(lang: String, onText: (String?) -> Unit) {
        delivered = false
        val deliver: (String?) -> Unit = { t ->
            if (!delivered) {
                delivered = true
                cleanup()
                onText(t)
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listenOffline(deliver); return
        }
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = sr
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    deliver(text)
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> deliver(null)
                        else -> {
                            // recogniser not usable here (busy / locked / no network) → offline fallback
                            recognizer?.destroy(); recognizer = null
                            listenOffline(deliver)
                        }
                    }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }
            sr.startListening(intent)
        } catch (e: Exception) {
            listenOffline(deliver)
        }
    }

    private fun listenOffline(deliver: (String?) -> Unit) {
        val model = WakeEngine.model ?: run { deliver(null); return }
        try {
            val rec = Recognizer(model, 16000f)
            val svc = SpeechService(rec, 16000f)
            vosk = svc
            svc.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) {
                    val t = textOf(hypothesis)
                    if (t.isNotBlank()) deliver(t)
                }

                override fun onFinalResult(hypothesis: String?) {
                    val t = textOf(hypothesis)
                    deliver(t.ifBlank { null })
                }

                override fun onError(exception: Exception?) = deliver(null)
                override fun onTimeout() = deliver(null)
            }, 7000)
        } catch (e: Exception) {
            deliver(null)
        }
    }

    private fun textOf(json: String?): String =
        try { JSONObject(json ?: "{}").optString("text", "") } catch (e: Exception) { "" }

    fun cleanup() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { vosk?.stop(); vosk?.shutdown() } catch (_: Exception) {}
        vosk = null
    }
}
