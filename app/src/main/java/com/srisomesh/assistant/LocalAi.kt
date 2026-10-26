package com.srisomesh.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * On-phone AI (Gemma model file you add once in Settings). Runs fully offline.
 * Small phone models are best at short answers; deeper chats will come from your home AI later.
 */
object LocalAi {
    @Volatile
    private var llm: LlmInference? = null
    private var loadedPath: String? = null
    private val history = ArrayDeque<Pair<String, String>>()

    fun hasModel(ctx: Context): Boolean {
        val p = Prefs(ctx).aiModelPath ?: return false
        return File(p).exists()
    }

    @Synchronized
    private fun engine(ctx: Context): LlmInference? {
        val path = Prefs(ctx).aiModelPath ?: return null
        if (!File(path).exists()) return null
        if (llm != null && loadedPath == path) return llm
        try { llm?.close() } catch (_: Exception) {}
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(768)
            .setMaxTopK(40)
            .build()
        llm = LlmInference.createFromOptions(ctx.applicationContext, opts)
        loadedPath = path
        return llm
    }

    fun reset() = synchronized(history) { history.clear() }

    /** Blocking — call from a background thread. */
    fun ask(ctx: Context, question: String): String {
        val engine = try { engine(ctx) } catch (e: Exception) {
            return "The AI model couldn't load. Try choosing the model file again in settings."
        } ?: return "The AI model isn't added yet. Add it in settings."

        val telugu = Speaker.isTelugu(question)
        val system = buildString {
            append("You are ${Prefs(ctx).wakeName}, Somesh's personal phone assistant. ")
            append("Your reply is spoken aloud, so answer in 1 to 3 short sentences, plain words, no lists, no symbols, no emojis. ")
            if (telugu) append("Reply in Telugu script.") else append("Reply in the same language as the question.")
        }
        val prompt = buildString {
            append("<start_of_turn>user\n").append(system).append("<end_of_turn>\n")
            append("<start_of_turn>model\nOkay.<end_of_turn>\n")
            synchronized(history) {
                history.forEach { (q, a) ->
                    append("<start_of_turn>user\n").append(q).append("<end_of_turn>\n")
                    append("<start_of_turn>model\n").append(a).append("<end_of_turn>\n")
                }
            }
            append("<start_of_turn>user\n").append(question).append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        val raw = try { engine.generateResponse(prompt) } catch (e: Exception) {
            reset()
            return "Sorry, the AI had a problem. Ask again."
        }
        val answer = raw.substringBefore("<end_of_turn>")
            .replace(Regex("[*#_`>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Sorry, I don't have an answer for that." }
        synchronized(history) {
            history.addLast(question to answer)
            while (history.size > 4) history.removeFirst()
        }
        return answer
    }
}
