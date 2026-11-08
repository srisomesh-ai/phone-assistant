package com.srisomesh.speed

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Per-device checks that are genuinely observable from a peer on the LAN:
 *  - reachability + round-trip latency (does it answer, how fast)
 *  - which common services it runs (so we can say what the device IS)
 * None of this reveals the device's internet access or login — that isn't visible from here.
 */
object DeviceProbe {

    /** Common ports → a plain-language label for what the device is/does. */
    val PORTS = listOf(
        21 to "FTP file share", 22 to "SSH", 23 to "Telnet", 53 to "DNS server",
        80 to "Web page", 139 to "Windows share", 443 to "Web page (secure)",
        445 to "Windows/SMB share", 515 to "Printer", 548 to "Apple file share",
        554 to "Camera (RTSP)", 631 to "Printer (IPP)", 1883 to "Smart-home (MQTT)",
        3389 to "Remote Desktop", 5000 to "NAS/UPnP", 5555 to "Android debug (ADB)",
        8008 to "Chromecast", 8080 to "Web page (alt)", 8443 to "Web (secure alt)",
        9100 to "Printer (raw)", 32400 to "Plex media", 62078 to "iPhone sync"
    )

    data class Ping(val sent: Int, val received: Int, val minMs: Long, val avgMs: Long, val maxMs: Long) {
        val lossPct get() = if (sent == 0) 0 else (sent - received) * 100 / sent
    }

    fun ping(ip: String, count: Int = 5, onEach: (i: Int, ok: Boolean, ms: Long) -> Unit): Ping {
        val times = ArrayList<Long>()
        repeat(count) { i ->
            val ms = reach(ip, 1000)
            if (ms >= 0) { times.add(ms); onEach(i, true, ms) } else onEach(i, false, -1)
            if (i < count - 1) Thread.sleep(300)
        }
        return if (times.isEmpty()) Ping(count, 0, 0, 0, 0)
        else Ping(count, times.size, times.min(), times.average().toLong(), times.max())
    }

    /** One reachability attempt; returns round-trip ms or -1. ICMP first, then a TCP knock. */
    private fun reach(ip: String, timeout: Int): Long {
        val addr = InetAddress.getByName(ip)
        val t0 = System.nanoTime()
        try { if (addr.isReachable(timeout)) return (System.nanoTime() - t0) / 1_000_000 } catch (_: Exception) {}
        for (p in intArrayOf(80, 443, 139, 445, 22, 7)) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, p), timeout); return (System.nanoTime() - t0) / 1_000_000 }
            } catch (_: Exception) {}
        }
        return -1
    }

    @Volatile var cancelScan = false

    /** Knock on each known port; reports open ones as it goes. Returns the list of open (port,label). */
    fun identify(ip: String, onPort: (done: Int, total: Int) -> Unit, onOpen: (port: Int, label: String) -> Unit): List<Pair<Int, String>> {
        cancelScan = false
        val open = ArrayList<Pair<Int, String>>()
        PORTS.forEachIndexed { i, (port, label) ->
            if (cancelScan) return open
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 400)
                    open.add(port to label); onOpen(port, label)
                }
            } catch (_: Exception) {}
            onPort(i + 1, PORTS.size)
        }
        return open
    }

    fun hasWeb(open: List<Pair<Int, String>>): Int? =
        open.map { it.first }.firstOrNull { it == 80 || it == 8080 || it == 443 || it == 8443 || it == 8008 || it == 5000 }
}
