package com.srisomesh.speed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** What the phone is connected to right now: Wi-Fi or mobile data, and how good the link is. */
data class Connection(
    val online: Boolean,           // has a network that says it reaches the internet
    val kind: String,              // "Wi-Fi", "Mobile data", "Ethernet", "Offline"
    val name: String?,             // Wi-Fi name or mobile operator
    val tech: String?,             // "Wi-Fi 6 · 5 GHz" or "4G (LTE)"
    val signal: String?,           // "-58 dBm · Excellent"
    val linkSpeed: String?,        // Wi-Fi link rate
    val estimate: String?,         // Android's own bandwidth estimate
    val metered: Boolean,
    val vpn: Boolean,
)

object NetInfo {

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun read(ctx: Context): Connection {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?: return Connection(false, "Offline", null, null, null, null, null, false, false)

        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val down = caps.linkDownstreamBandwidthKbps
        val up = caps.linkUpstreamBandwidthKbps
        val estimate = if (down > 0) "↓ ${kbps(down)}  ↑ ${kbps(up)}" else null

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi(ctx, validated, estimate, metered, vpn)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular(ctx, validated, estimate, metered, vpn)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                Connection(validated, "Ethernet", null, null, null, null, estimate, metered, vpn)
            else -> Connection(validated, if (vpn) "VPN" else "Other", null, null, null, null, estimate, metered, vpn)
        }
    }

    @Suppress("DEPRECATION")
    private fun wifi(ctx: Context, ok: Boolean, est: String?, metered: Boolean, vpn: Boolean): Connection {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val info = wm?.connectionInfo
        var name: String? = info?.ssid?.trim('"')
        if (name == null || name == "<unknown ssid>" || name.isBlank()) name = "(turn on Location to see name)"
        val freq = info?.frequency ?: 0
        val band = when {
            freq in 2400..2500 -> "2.4 GHz"
            freq in 4900..5900 -> "5 GHz"
            freq >= 5925 -> "6 GHz"
            else -> null
        }
        val std = if (Build.VERSION.SDK_INT >= 30) when (info?.wifiStandard) {
            4 -> "Wi-Fi 4"; 5 -> "Wi-Fi 5"; 6 -> "Wi-Fi 6"; 7 -> "Wi-Fi 7"; 1 -> "Wi-Fi (legacy)"; else -> null
        } else null
        val rssi = info?.rssi ?: -127
        val signal = if (rssi > -127) "$rssi dBm · ${quality(rssi, -55, -67, -75)}" else null
        val link = info?.linkSpeed?.takeIf { it > 0 }?.let { l ->
            if (Build.VERSION.SDK_INT >= 29 && info.rxLinkSpeedMbps > 0)
                "↓ ${info.rxLinkSpeedMbps}  ↑ ${info.txLinkSpeedMbps} Mbps"
            else "$l Mbps"
        }
        return Connection(ok, "Wi-Fi", name, listOfNotNull(std, band).joinToString(" · ").ifBlank { null },
            signal, link, est, metered, vpn)
    }

    @SuppressLint("MissingPermission")
    private fun cellular(ctx: Context, ok: Boolean, est: String?, metered: Boolean, vpn: Boolean): Connection {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val name = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        val canRead = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val tech = if (canRead) try { generation(tm!!.dataNetworkType) } catch (_: Exception) { null }
        else "allow Phone permission to see 4G/5G"
        var signal: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val ss = tm?.signalStrength
                val dbm = ss?.cellSignalStrengths?.firstOrNull()?.dbm
                val lvl = ss?.level
                if (dbm != null && dbm != Int.MAX_VALUE && lvl != null)
                    signal = "$dbm dBm · ${listOf("None", "Poor", "Fair", "Good", "Excellent").getOrElse(lvl) { "–" }}"
            } catch (_: Exception) { }
        }
        val roaming = try { tm?.isNetworkRoaming == true } catch (_: Exception) { false }
        return Connection(ok, "Mobile data" + if (roaming) " (roaming)" else "", name, tech, signal, null, est, metered, vpn)
    }

    private fun generation(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi calling"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> "Unknown"
    }

    private fun quality(dbm: Int, exc: Int, good: Int, fair: Int) = when {
        dbm >= exc -> "Excellent"; dbm >= good -> "Good"; dbm >= fair -> "Fair"; else -> "Poor"
    }

    private fun kbps(k: Int): String = if (k >= 1000) "${k / 1000} Mbps" else "$k kbps"

    /** Total bytes in/out across the phone — sampled each second to show live usage. */
    fun totalBytes(): Pair<Long, Long> = TrafficStats.getTotalRxBytes() to TrafficStats.getTotalTxBytes()
}
