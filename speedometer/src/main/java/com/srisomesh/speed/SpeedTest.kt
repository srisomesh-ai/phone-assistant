package com.srisomesh.speed

import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Internet speed test against Cloudflare's public speed-test servers (the same ones
 * speed.cloudflare.com uses). Measures ping, jitter, download and upload over several
 * parallel connections, so it works the same on Wi-Fi and mobile data.
 */
class SpeedTest(private val listener: Listener) {

    enum class Phase { PING, DOWNLOAD, UPLOAD, DONE }

    interface Listener {
        fun onPhase(phase: Phase)
        fun onLive(phase: Phase, mbps: Double, progress: Float)
        fun onPing(pingMs: Double, jitterMs: Double)
        fun onResult(r: Result)
        fun onError(msg: String)
    }

    data class Result(
        val pingMs: Double, val jitterMs: Double,
        val downMbps: Double, val upMbps: Double,
        val isp: String?, val server: String?, val ip: String?,
        val bytesUsed: Long,
    )

    private val cancelled = AtomicBoolean(false)
    fun cancel() = cancelled.set(true)
    val isCancelled get() = cancelled.get()

    fun run() {
        try {
            val meta = meta()
            listener.onPhase(Phase.PING)
            val (ping, jitter) = ping()
            listener.onPing(ping, jitter)
            if (cancelled.get()) return

            listener.onPhase(Phase.DOWNLOAD)
            val (down, dBytes) = transfer(Phase.DOWNLOAD, threads = 4, seconds = 10, byteCap = 250L shl 20)
            if (cancelled.get()) return

            listener.onPhase(Phase.UPLOAD)
            val (up, uBytes) = transfer(Phase.UPLOAD, threads = 3, seconds = 8, byteCap = 80L shl 20)
            if (cancelled.get()) return

            listener.onPhase(Phase.DONE)
            listener.onResult(Result(ping, jitter, down, up,
                meta.optString("asOrganization").ifBlank { null },
                listOf(meta.optString("city"), meta.optString("colo")).filter { it.isNotBlank() }
                    .joinToString(" · ").ifBlank { null },
                meta.optString("clientIp").ifBlank { null },
                dBytes + uBytes))
        } catch (e: Exception) {
            if (!cancelled.get()) listener.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "SomeshSpeedometer/1.0")
        }

    /** ISP, server city and your public IP. Optional — empty if unavailable. */
    private fun meta(): JSONObject = try {
        val c = open("/meta")
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(txt)
    } catch (_: Exception) { JSONObject() }

    /** 12 tiny requests on one kept-alive connection; first is dropped (it includes the handshake). */
    private fun ping(): Pair<Double, Double> {
        val times = mutableListOf<Double>()
        repeat(12) { i ->
            if (cancelled.get()) return@repeat
            val t0 = System.nanoTime()
            val c = open("/__down?bytes=0")
            c.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - t0) / 1e6
            if (i > 0) times.add(ms)
            listener.onLive(Phase.PING, 0.0, (i + 1) / 12f)
        }
        if (times.isEmpty()) throw IllegalStateException("No reply from test server")
        val sorted = times.sorted()
        val ping = sorted.take((sorted.size + 1) / 2).average()   // average of the faster half
        val jitter = times.zipWithNext { a, b -> abs(a - b) }.average().takeIf { !it.isNaN() } ?: 0.0
        return ping to jitter
    }

    /**
     * Runs [threads] parallel downloads or uploads for [seconds]. The first 1.5 s are ignored
     * (connection ramp-up) so the figure reflects the steady speed.
     * Returns (Mbps, bytes used).
     */
    private fun transfer(phase: Phase, threads: Int, seconds: Int, byteCap: Long): Pair<Double, Long> {
        val total = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val start = System.nanoTime()
        val endAt = start + seconds * 1_000_000_000L
        val workers = (0 until threads).map {
            Thread {
                val buf = ByteArray(64 * 1024)
                while (!stop.get() && !cancelled.get()) {
                    var c: HttpURLConnection? = null
                    try {
                        if (phase == Phase.DOWNLOAD) {
                            c = open("/__down?bytes=${25_000_000}")
                            c.inputStream.use { ins ->
                                while (!stop.get() && !cancelled.get()) {
                                    val n = ins.read(buf); if (n < 0) break
                                    total.addAndGet(n.toLong())
                                }
                            }
                        } else {
                            val size = 8 * 1024 * 1024
                            c = open("/__up").apply {
                                requestMethod = "POST"; doOutput = true
                                setFixedLengthStreamingMode(size)
                                setRequestProperty("Content-Type", "application/octet-stream")
                            }
                            val out: OutputStream = c.outputStream
                            var sent = 0
                            while (sent < size && !stop.get() && !cancelled.get()) {
                                val n = minOf(buf.size, size - sent)
                                out.write(buf, 0, n); sent += n
                                total.addAndGet(n.toLong())
                            }
                            if (sent >= size) { out.close(); c.responseCode }
                        }
                    } catch (_: Exception) {
                        if (stop.get() || cancelled.get()) break
                        Thread.sleep(200)
                    } finally {
                        try { c?.disconnect() } catch (_: Exception) { }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        val warmNs = 1_500_000_000L
        var warmBytes = -1L
        var lastBytes = 0L
        var lastT = start
        var smooth = 0.0
        while (true) {
            Thread.sleep(250)
            val now = System.nanoTime()
            val b = total.get()
            if (warmBytes < 0 && now - start >= warmNs) warmBytes = b
            val inst = (b - lastBytes) * 8 / ((now - lastT) / 1e9) / 1e6
            smooth = if (smooth == 0.0) inst else smooth * 0.6 + inst * 0.4
            lastBytes = b; lastT = now
            listener.onLive(phase, smooth, ((now - start).toFloat() / (endAt - start)).coerceIn(0f, 1f))
            if (now >= endAt || b >= byteCap || cancelled.get()) break
        }
        stop.set(true)
        val end = System.nanoTime()
        val bytes = total.get()
        workers.forEach { it.join(1500) }

        val mbps = if (warmBytes >= 0 && end - start > warmNs)
            (bytes - warmBytes) * 8 / ((end - start - warmNs) / 1e9) / 1e6
        else bytes * 8 / ((end - start) / 1e9) / 1e6
        return mbps to bytes
    }

    companion object {
        private const val BASE = "https://speed.cloudflare.com"
    }
}
