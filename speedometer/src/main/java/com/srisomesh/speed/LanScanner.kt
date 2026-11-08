package com.srisomesh.speed

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the devices on the same Wi-Fi/LAN as the phone and that respond to it.
 *
 * What it can and cannot know (be honest with the user):
 *  - It lists devices on the phone's own IPv4 subnet that answer a ping or a TCP knock,
 *    with IP, name (mDNS / reverse-DNS / NetBIOS) and MAC+vendor where Android exposes them.
 *  - It CANNOT tell which devices are "logged in" to a captive portal or which have internet
 *    access — that is controller/RADIUS/firewall state, invisible to any phone app.
 *  - On networks with client/AP isolation (common on company Wi-Fi with per-user logins) devices
 *    can't see each other at all, so only the gateway and the phone may appear.
 */
class LanScanner(private val ctx: Context) {

    data class Device(
        val ip: String,
        var name: String? = null,
        var mac: String? = null,
        var vendor: String? = null,
        var pingMs: Long = -1,
        var isGateway: Boolean = false,
        var isSelf: Boolean = false,
        var openHint: String? = null,     // e.g. "web", "SMB", "SSH" — a service we could reach
    )

    interface Listener {
        fun onInfo(ssid: String?, selfIp: String?, gatewayIp: String?, subnet: String?, hostCount: Int)
        fun onProgress(done: Int, total: Int)
        fun onFound(device: Device)        // may be called again to update the same IP
        fun onDone(devices: List<Device>, isolated: Boolean)
        fun onError(msg: String)
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    private val found = ConcurrentHashMap<String, Device>()

    fun scan(listener: Listener) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm?.getLinkProperties(cm.activeNetwork)
            val v4: LinkAddress? = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            if (v4 == null) { listener.onError("No Wi-Fi/LAN connection. Connect to Wi-Fi first."); return }

            val self = v4.address.hostAddress ?: return
            val prefix = v4.prefixLength
            val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress

            @Suppress("DEPRECATION")
            val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val ssid = wifi?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

            // Build the list of host IPs to probe. Cap at a /24-sized sweep around our own address
            // so a wide corporate subnet doesn't turn into tens of thousands of pings.
            val hosts = hostList(self, prefix)
            listener.onInfo(ssid, self, gateway, "$self/$prefix", hosts.size)

            // Multicast lock lets mDNS replies through.
            val lock = try { wifi?.createMulticastLock("lan-scan")?.apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { null }

            // Start name discovery in the background while we sweep.
            val mdns = startMdns(listener)

            // Seed anything already in the ARP cache (often empty on Android 10+, but free to try).
            readArp().forEach { (ip, mac) ->
                if (ip in hosts) upsert(listener, ip) { it.mac = mac; it.vendor = Oui.vendor(mac) }
            }

            val done = AtomicInteger(0)
            val total = hosts.size
            val pool = Executors.newFixedThreadPool(48)
            val latch = CountDownLatch(hosts.size)
            for (ip in hosts) {
                pool.execute {
                    try {
                        if (cancelled) return@execute
                        val ping = probe(ip)
                        if (ping >= 0) {
                            upsert(listener, ip) { d ->
                                d.pingMs = ping
                                d.isSelf = ip == self
                                d.isGateway = ip == gateway
                                if (d.name == null) d.name = reverseName(ip)
                                if (d.mac == null) readArpFor(ip)?.let { m -> d.mac = m; d.vendor = Oui.vendor(m) }
                                if (d.name == null) d.name = netbiosName(ip)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        listener.onProgress(done.incrementAndGet(), total)
                        latch.countDown()
                    }
                }
            }
            latch.await(60, TimeUnit.SECONDS)
            pool.shutdownNow()

            // Give mDNS a moment, then stop it.
            Thread.sleep(if (cancelled) 0 else 1500)
            stopMdns(mdns)
            try { lock?.release() } catch (_: Exception) {}

            val list = found.values.sortedWith(compareBy({ !it.isSelf }, { !it.isGateway }, { ipKey(it.ip) }))
            // If nothing but ourselves/gateway answered, the network is very likely isolating clients.
            val others = list.count { !it.isSelf && !it.isGateway }
            listener.onDone(list, isolated = others == 0 && !cancelled)
        } catch (e: Exception) {
            if (!cancelled) listener.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun upsert(listener: Listener, ip: String, edit: (Device) -> Unit) {
        val d = found.getOrPut(ip) { Device(ip) }
        synchronized(d) { edit(d) }
        listener.onFound(d)
    }

    /** ICMP (via isReachable) first; then a knock on common TCP ports, which also hints what the device is. */
    private fun probe(ip: String): Long {
        val addr = InetAddress.getByName(ip)
        val t0 = System.nanoTime()
        try { if (addr.isReachable(500)) return (System.nanoTime() - t0) / 1_000_000 } catch (_: Exception) {}
        val ports = intArrayOf(80, 443, 139, 445, 22, 62078, 8080, 7)
        for (p in ports) {
            if (cancelled) return -1
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, p), 350)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    found[ip]?.let { it.openHint = hint(p) }
                    return ms
                }
            } catch (_: Exception) {}
        }
        return -1
    }

    private fun hint(port: Int) = when (port) {
        80, 8080, 443 -> "web"; 139, 445 -> "SMB"; 22 -> "SSH"; 62078 -> "iPhone"; else -> null
    }

    private fun reverseName(ip: String): String? = try {
        val h = InetAddress.getByName(ip).canonicalHostName
        if (h != null && h != ip) h.removeSuffix(".local").removeSuffix(".") else null
    } catch (_: Exception) { null }

    /** NetBIOS node-status query (UDP 137) — gets the Windows/Samba computer name. */
    private fun netbiosName(ip: String): String? {
        return try {
            val q = byteArrayOf(
                0x82.toByte(), 0x28, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x20, 0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x00, 0x00, 0x21, 0x00, 0x01
            )
            DatagramSocket().use { s ->
                s.soTimeout = 500
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(512)
                val resp = DatagramPacket(buf, buf.size)
                s.receive(resp)
                val count = buf[56].toInt()
                if (count <= 0) return null
                val raw = String(buf, 57, 15).trim().trim('\u0000')
                raw.ifBlank { null }
            }
        } catch (_: Exception) { null }
    }

    private fun readArp(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4) {
                    val ip = p[0]; val mac = p[3]
                    if (mac.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) && mac != "00:00:00:00:00:00")
                        out[ip] = mac.uppercase()
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun readArpFor(ip: String): String? = readArp()[ip]

    // ---- mDNS (device names like "Johns-iPhone", "living-room-tv") ----

    private var nsd: NsdManager? = null
    private val listeners = Collections.synchronizedList(mutableListOf<NsdManager.DiscoveryListener>())

    private fun startMdns(out: Listener): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        nsd = ctx.getSystemService(NsdManager::class.java) ?: return false
        val types = listOf(
            "_workstation._tcp.", "_googlecast._tcp.", "_airplay._tcp.", "_raop._tcp.",
            "_http._tcp.", "_smb._tcp.", "_ipp._tcp.", "_spotify-connect._tcp.", "_companion-link._tcp."
        )
        types.forEach { type ->
            try {
                val dl = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(s: String?) {}
                    override fun onDiscoveryStopped(s: String?) {}
                    override fun onStartDiscoveryFailed(s: String?, e: Int) {}
                    override fun onStopDiscoveryFailed(s: String?, e: Int) {}
                    override fun onServiceLost(s: NsdServiceInfo?) {}
                    override fun onServiceFound(info: NsdServiceInfo?) {
                        if (info == null) return
                        try {
                            @Suppress("DEPRECATION")
                            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(s: NsdServiceInfo?, e: Int) {}
                                override fun onServiceResolved(s: NsdServiceInfo?) {
                                    val host = s?.host?.hostAddress ?: return
                                    val nm = s.serviceName?.substringBefore(" (")?.trim()
                                    if (!nm.isNullOrBlank()) upsert(out, host) { if (it.name == null || it.name!!.contains(".")) it.name = nm }
                                }
                            })
                        } catch (_: Exception) {}
                    }
                }
                listeners.add(dl)
                nsd?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl)
            } catch (_: Exception) {}
        }
        return true
    }

    private fun stopMdns(started: Boolean) {
        if (!started) return
        synchronized(listeners) {
            listeners.forEach { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
            listeners.clear()
        }
    }

    // ---- subnet maths ----

    private fun hostList(self: String, prefix: Int): List<String> {
        val selfInt = ipToInt(self)
        val p = prefix.coerceIn(16, 30)
        // Cap the sweep to the lowest 8 bits (a /24 window) so big subnets stay fast.
        val effBits = maxOf(p, 24)
        val mask = if (effBits == 32) -1 else (-1 shl (32 - effBits))
        val base = selfInt and mask
        val size = (1 shl (32 - effBits))
        val list = ArrayList<String>(size)
        for (i in 0 until size) {
            val host = (base or i)
            if (i == 0 || i == size - 1) continue // network / broadcast
            list.add(intToIp(host))
        }
        return list
    }

    private fun ipToInt(ip: String): Int {
        val p = ip.split(".")
        return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
    }

    private fun intToIp(v: Int) = "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun ipKey(ip: String) = try { ipToInt(ip).toLong() and 0xFFFFFFFFL } catch (_: Exception) { 0L }
}
