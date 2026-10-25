package com.ibrahim.routermanager.drivers

import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Works with any router: pings the whole /24, reverse-resolves names and probes a few ports to guess the device type.
 * Android 10+ hides MAC addresses from apps, so the MAC is only filled in when the system still exposes it.
 */
class ScanDriver(private val router: RouterConfig) : RouterDriver {

    override fun clients(): List<Client> {
        val prefix = router.host.substringBeforeLast('.', "")
        require(prefix.isNotEmpty()) { "عنوان IP غير صحيح" }
        val me = localIps()
        val pool = Executors.newFixedThreadPool(64)
        try {
            val jobs = (1..254).map { n ->
                pool.submit<Client?> { probe("$prefix.$n", me) }
            }
            val found = jobs.mapNotNull { it.get(40, TimeUnit.SECONDS) }
            val arp = readArp()
            return found.map { c -> arp[c.ip]?.let { c.copy(mac = it) } ?: c }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probe(ip: String, me: Set<String>): Client? {
        val addr = InetAddress.getByName(ip)
        val start = System.nanoTime()
        val isMe = ip in me
        if (!isMe && !addr.isReachable(800)) return null
        val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
        val name = addr.canonicalHostName.takeIf { it != ip }.orEmpty()
        val labels = mutableListOf<String>()
        if (isMe) labels += "هذا الجهاز"
        if (ip == router.host) labels += "الراوتر"
        guessType(ip)?.let { labels += it }
        return Client(router.id, mac = ip, ip = ip, name = name, blocked = false, info = labels.joinToString(" • "), latencyMs = if (isMe) -1 else ms)
    }

    private fun guessType(ip: String): String? {
        fun open(port: Int) = runCatching {
            Socket().use { it.connect(InetSocketAddress(ip, port), 200); true }
        }.getOrDefault(false)
        return when {
            open(62078) -> "iPhone / iPad"
            open(554) -> "كاميرا مراقبة"
            open(9100) || open(631) || open(515) -> "طابعة"
            open(8009) -> "تلفزيون / Chromecast"
            open(445) || open(139) -> "كمبيوتر"
            open(80) || open(443) -> "صفحة ويب (جهاز شبكة؟)"
            else -> null
        }
    }

    /** IP -> MAC from the kernel ARP table; empty on Android 10+. */
    private fun readArp(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] to normalizeMac(p[3]) else null
        }.toMap()
    }.getOrDefault(emptyMap())

    override fun block(mac: String) = unsupported()
    override fun unblock(mac: String) = unsupported()
    override fun kick(mac: String) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("هذا النوع لا يدعم التحكم")

    companion object {
        fun localIps(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress.orEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }
}
