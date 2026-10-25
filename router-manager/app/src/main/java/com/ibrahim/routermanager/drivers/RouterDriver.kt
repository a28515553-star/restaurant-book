package com.ibrahim.routermanager.drivers

import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
import com.ibrahim.routermanager.data.RouterType

/** One implementation per router family. All calls are blocking; run them on Dispatchers.IO. */
interface RouterDriver {
    fun clients(): List<Client>

    /** Cuts the device off and keeps it off until [unblock]. */
    fun block(mac: String)
    fun unblock(mac: String)

    /** Disconnects the device now; it may reconnect. */
    fun kick(mac: String)
}

fun RouterConfig.driver(): RouterDriver = when (type) {
    RouterType.MIKROTIK -> MikroTikDriver(this)
    RouterType.OPENWRT -> OpenWrtDriver(this)
    RouterType.SCAN -> ScanDriver(this)
}

fun normalizeMac(mac: String) = mac.trim().uppercase().replace('-', ':')
