package com.ibrahim.routermanager.drivers

import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * RouterOS v7.1+ REST API (needs the `www` service enabled: /ip service enable www).
 * Blocking = a firewall drop rule on the device MAC, tagged with a comment so we can find and remove it.
 */
class MikroTikDriver(private val router: RouterConfig) : RouterDriver {
    private val base = "http://${router.host}/rest"
    private val auth = router.username to router.password

    private fun get(path: String) = JSONArray(httpCall("GET", "$base/$path", basicAuth = auth))

    override fun clients(): List<Client> {
        val blockedMacs = blockRules().map { it.second }.toSet()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Client>()

        val leases = get("ip/dhcp-server/lease")
        for (i in 0 until leases.length()) {
            val o = leases.getJSONObject(i)
            val mac = normalizeMac(o.optString("mac-address"))
            if (mac.isEmpty() || o.optString("status") != "bound") continue
            if (!seen.add(mac)) continue
            result += Client(
                router.id, mac,
                ip = o.optString("active-address").ifEmpty { o.optString("address") },
                name = o.optString("host-name").ifEmpty { o.optString("comment") },
                blocked = mac in blockedMacs,
            )
        }
        // Blocked devices no longer hold an active lease, but must stay visible so they can be unblocked.
        for (mac in blockedMacs - seen) {
            result += Client(router.id, mac, ip = "", name = "", blocked = true)
        }
        return result
    }

    override fun block(mac: String) {
        val m = normalizeMac(mac)
        if (blockRules().any { it.second == m }) return
        val rule = JSONObject()
            .put("chain", "forward")
            .put("action", "drop")
            .put("src-mac-address", m)
            .put("comment", "$TAG$m")
            .put("place-before", "0") // must sit above the accept rules to take effect
        httpCall("PUT", "$base/ip/firewall/filter", rule.toString(), auth)
        runCatching { kick(m) }
    }

    override fun unblock(mac: String) {
        val m = normalizeMac(mac)
        blockRules().filter { it.second == m }.forEach { (id, _) ->
            httpCall("DELETE", "$base/ip/firewall/filter/$id", basicAuth = auth)
        }
    }

    override fun kick(mac: String) {
        val m = normalizeMac(mac)
        // RouterOS has two wireless packages with different menu names; try both.
        for (menu in listOf("interface/wifi/registration-table", "interface/wireless/registration-table")) {
            val rows = runCatching { get(menu) }.getOrNull() ?: continue
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                if (normalizeMac(o.optString("mac-address")) == m) {
                    httpCall("POST", "$base/$menu/remove", JSONObject().put(".id", o.getString(".id")).toString(), auth)
                }
            }
        }
    }

    /** (rule id, MAC) of every rule this app created. */
    private fun blockRules(): List<Pair<String, String>> {
        val rules = get("ip/firewall/filter")
        return (0 until rules.length()).map { rules.getJSONObject(it) }
            .filter { it.optString("comment").startsWith(TAG) }
            .map { it.getString(".id") to it.getString("comment").removePrefix(TAG) }
    }

    private companion object {
        const val TAG = "rm-block:"
    }
}
