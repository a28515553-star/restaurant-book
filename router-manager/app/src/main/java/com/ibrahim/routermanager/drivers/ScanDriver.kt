package com.ibrahim.routermanager.drivers

import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Works with any router: pings the whole /24 and lists the hosts that answer.
 * Android does not expose MAC addresses to apps, so devices cannot be blocked from here.
 */
class ScanDriver(private val router: RouterConfig) : RouterDriver {

    override fun clients(): List<Client> {
        val prefix = router.host.substringBeforeLast('.', "")
        require(prefix.isNotEmpty()) { "عنوان IP غير صحيح" }
        val pool = Executors.newFixedThreadPool(48)
        try {
            val jobs = (1..254).map { n ->
                pool.submit<Client?> {
                    val ip = "$prefix.$n"
                    val addr = InetAddress.getByName(ip)
                    if (addr.isReachable(700)) {
                        val name = addr.canonicalHostName.takeIf { it != ip }.orEmpty()
                        Client(router.id, mac = ip, ip = ip, name = name, blocked = false)
                    } else null
                }
            }
            return jobs.mapNotNull { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    override fun block(mac: String) = unsupported()
    override fun unblock(mac: String) = unsupported()
    override fun kick(mac: String) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("هذا النوع لا يدعم التحكم، اختر MikroTik أو OpenWrt")
}
