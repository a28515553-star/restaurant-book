package com.ibrahim.routermanager.drivers

import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenWrt over the ubus JSON-RPC endpoint (rpcd + uhttpd-mod-ubus, present when LuCI is installed).
 * Wi-Fi clients come from hostapd; names/IPs from the DHCP lease file.
 * Blocking = a firewall REJECT rule on the device MAC, added through uci.
 */
class OpenWrtDriver(private val router: RouterConfig) : RouterDriver {
    private val url = "http://${router.host}/ubus"
    private var session: String? = null
    private var nextId = 1

    private fun call(obj: String, method: String, args: JSONObject = JSONObject()): JSONObject {
        val sid = session ?: login().also { session = it }
        return rawCall(sid, obj, method, args)
    }

    private fun rawCall(sid: String, obj: String, method: String, args: JSONObject): JSONObject {
        val req = JSONObject()
            .put("jsonrpc", "2.0").put("id", nextId++).put("method", "call")
            .put("params", JSONArray().put(sid).put(obj).put(method).put(args))
        val resp = JSONObject(httpCall("POST", url, req.toString()))
        resp.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
        val result = resp.getJSONArray("result")
        val code = result.getInt(0)
        if (code == 6) { // permission denied: session expired
            session = null
            throw IllegalStateException("انتهت الجلسة، أعد المحاولة")
        }
        if (code != 0) throw IllegalStateException("ubus error $code ($obj.$method)")
        return result.optJSONObject(1) ?: JSONObject()
    }

    private fun login(): String {
        val args = JSONObject().put("username", router.username).put("password", router.password)
        val res = try {
            rawCall(NULL_SESSION, "session", "login", args)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("فشل تسجيل الدخول، تحقق من اسم المستخدم وكلمة المرور")
        }
        return res.getString("ubus_rpc_session")
    }

    private fun wifiInterfaces(): List<String> {
        val req = JSONObject()
            .put("jsonrpc", "2.0").put("id", nextId++).put("method", "list")
            .put("params", JSONArray().put(session ?: login().also { session = it }).put("hostapd.*"))
        val resp = JSONObject(httpCall("POST", url, req.toString()))
        val names = resp.optJSONObject("result")?.keys()?.asSequence()?.toList() ?: emptyList()
        return names.filter { it.startsWith("hostapd.") }
    }

    override fun clients(): List<Client> {
        val leases = leases()
        val blockedMacs = blockRules().keys
        val macs = linkedSetOf<String>()
        for (iface in wifiInterfaces()) {
            val c = call(iface, "get_clients").optJSONObject("clients") ?: continue
            c.keys().forEach { macs += normalizeMac(it) }
        }
        // DHCP leases also cover wired devices; leases may be stale so Wi-Fi presence is the "online" signal.
        macs += leases.keys
        return (macs + blockedMacs).map { mac ->
            val lease = leases[mac]
            Client(router.id, mac, ip = lease?.first.orEmpty(), name = lease?.second.orEmpty(), blocked = mac in blockedMacs)
        }
    }

    /** MAC -> (ip, hostname) from /tmp/dhcp.leases: "expiry mac ip host clientid". */
    private fun leases(): Map<String, Pair<String, String>> {
        val data = call("file", "read", JSONObject().put("path", "/tmp/dhcp.leases")).optString("data")
        return data.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 4 }
            .associate { normalizeMac(it[1]) to (it[2] to it[3].takeIf { n -> n != "*" }.orEmpty()) }
    }

    override fun block(mac: String) {
        val m = normalizeMac(mac)
        if (m !in blockRules()) {
            val sec = call("uci", "add", JSONObject().put("config", "firewall").put("type", "rule").put(
                "values", JSONObject()
                    .put("name", "$TAG$m").put("src", "lan").put("dest", "*")
                    .put("src_mac", m).put("target", "REJECT")
            )).getString("section")
            check(sec.isNotEmpty())
            applyFirewall()
        }
        runCatching { kick(m) }
    }

    override fun unblock(mac: String) {
        val sec = blockRules()[normalizeMac(mac)] ?: return
        call("uci", "delete", JSONObject().put("config", "firewall").put("section", sec))
        applyFirewall()
    }

    override fun kick(mac: String) {
        val args = JSONObject().put("addr", normalizeMac(mac).lowercase()).put("deauth", true).put("reason", 5).put("ban_time", 0)
        for (iface in wifiInterfaces()) runCatching { call(iface, "del_client", args) }
    }

    /** MAC -> uci section id of every rule this app created. */
    private fun blockRules(): Map<String, String> {
        val values = call("uci", "get", JSONObject().put("config", "firewall").put("type", "rule")).optJSONObject("values")
            ?: return emptyMap()
        return values.keys().asSequence()
            .filter { values.getJSONObject(it).optString("name").startsWith(TAG) }
            .associate { values.getJSONObject(it).getString("name").removePrefix(TAG) to it }
    }

    private fun applyFirewall() {
        call("uci", "commit", JSONObject().put("config", "firewall"))
        call("file", "exec", JSONObject().put("command", "/etc/init.d/firewall").put("params", JSONArray().put("reload")))
    }

    private companion object {
        const val TAG = "rm-block-"
        const val NULL_SESSION = "00000000000000000000000000000000"
    }
}
