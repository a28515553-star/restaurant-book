package com.ibrahim.routermanager.data

enum class RouterType(val label: String, val canControl: Boolean) {
    MIKROTIK("MikroTik (REST API)", true),
    OPENWRT("OpenWrt (ubus)", true),
    SCAN("عام - فحص الشبكة فقط (بدون تحكم)", false),
}

data class RouterConfig(
    val id: String,
    val name: String,
    val host: String,
    val type: RouterType,
    val username: String,
    val password: String,
)

data class Client(
    val routerId: String,
    val mac: String,
    val ip: String,
    val name: String,
    val blocked: Boolean,
)

sealed interface RouterState {
    data object Loading : RouterState
    data class Ok(val clients: List<Client>) : RouterState
    data class Failed(val message: String) : RouterState
}
