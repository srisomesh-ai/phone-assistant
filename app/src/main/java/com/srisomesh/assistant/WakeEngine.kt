package com.srisomesh.assistant

import android.content.Context
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline speech model (Vosk, Indian English) used to hear the wake phrase with the screen off.
 * Downloaded once (~36 MB) on first setup, then works without internet.
 */
object WakeEngine {
    private val MODEL_URLS = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
    )

    @Volatile
    var model: Model? = null
        private set

    fun isDownloaded(ctx: Context): Boolean {
        val dir = Prefs(ctx).voskModelDir ?: return false
        return File(dir, "am").exists() || File(dir, "conf").exists()
    }

    /** Loads the model (blocking — call off the main thread). */
    @Synchronized
    fun load(ctx: Context): Model? {
        model?.let { return it }
        if (!isDownloaded(ctx)) return null
        return try {
            Model(Prefs(ctx).voskModelDir).also { model = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads and unzips the model (blocking). [progress] gets 0..100. Returns error text or null. */
    fun download(ctx: Context, progress: (Int) -> Unit): String? {
        var lastError = "Download failed"
        for (url in MODEL_URLS) {
            try {
                val zip = File(ctx.cacheDir, "vosk.zip")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    lastError = "Server said ${conn.responseCode}"
                    continue
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress((done * 90 / total).toInt())
                        }
                    }
                }
                val root = File(ctx.filesDir, "vosk")
                root.deleteRecursively()
                root.mkdirs()
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val f = File(root, e.name)
                        if (!f.canonicalPath.startsWith(root.canonicalPath)) continue
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { zin.copyTo(it) }
                        }
                    }
                }
                zip.delete()
                val modelDir = root.listFiles()?.firstOrNull { File(it, "am").exists() || File(it, "conf").exists() }
                    ?: root.takeIf { File(it, "am").exists() }
                    ?: run { lastError = "Model files missing"; null }
                if (modelDir != null) {
                    Prefs(ctx).voskModelDir = modelDir.absolutePath
                    model = null
                    progress(100)
                    return null
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Download failed"
            }
        }
        return lastError
    }
}

/** Decides whether something the offline recogniser heard is the wake phrase. */
object WakeMatcher {
    private val WAKE_PREFIXES = listOf("hey", "hi", "hay", "he", "a", "ok", "okay")

    fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun matches(ctx: Context, heard: String): Boolean {
        val text = normalize(heard)
        if (text.isEmpty()) return false
        val prefs = Prefs(ctx)
        val name = normalize(prefs.wakeName).replace(" ", "")
        if (name.length < 3) return false

        // 1. Phrases learned with "Teach wake word" — captured with the SAME offline
        //    engine that listens live, so this is the most reliable path.
        for (v in prefs.wakeVariants) {
            val vn = normalize(v)
            if (vn.length < 3) continue
            if (text.contains(vn)) return true
            if (windowSimilarity(text, vn) >= 0.72) return true
        }
        // 2. The name heard anywhere, with or without a "hey" in front.
        val words = text.split(" ").filter { it.isNotEmpty() }
        for (i in words.indices) {
            for (len in 1..3) {
                val end = i + len
                if (end > words.size) break
                val candidate = words.subList(i, end).joinToString("")
                if (similarity(candidate, name) >= 0.68) return true
                if (name.length >= 5 && candidate.contains(name)) return true
            }
        }
        return false
    }

    /** Best similarity of [target] against any same-length run of words in [text]. */
    private fun windowSimilarity(text: String, target: String): Double {
        val tw = text.split(" ")
        val n = target.split(" ").size
        var best = 0.0
        for (k in maxOf(1, n - 1)..(n + 1)) {
            if (k > tw.size) continue
            for (i in 0..(tw.size - k)) {
                val chunk = tw.subList(i, i + k).joinToString(" ")
                best = maxOf(best, similarity(chunk, target))
            }
        }
        return best
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
