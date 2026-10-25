package com.ibrahim.routermanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.DeviceNotes
import com.ibrahim.routermanager.data.RouterConfig
import com.ibrahim.routermanager.data.RouterType
import com.ibrahim.routermanager.drivers.ScanDriver
import com.ibrahim.routermanager.data.RouterState
import com.ibrahim.routermanager.data.RouterStore
import com.ibrahim.routermanager.drivers.driver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Action(val label: String) {
    BLOCK("حظر"), UNBLOCK("إلغاء الحظر"), KICK("إيقاف (فصل الآن)")
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RouterStore(app)

    private val notes = DeviceNotes(app)

    private val _names = MutableStateFlow(notes.names())
    val names: StateFlow<Map<String, String>> = _names

    /** Devices seen before; null until the first successful scan, so the first run flags nothing as new. */
    private val _known = MutableStateFlow(notes.known().takeIf { it.isNotEmpty() })
    val known: StateFlow<Set<String>?> = _known

    private val _routers = MutableStateFlow(store.load().ifEmpty { defaultRouters() })
    val routers: StateFlow<List<RouterConfig>> = _routers

    private val _states = MutableStateFlow<Map<String, RouterState>>(emptyMap())
    val states: StateFlow<Map<String, RouterState>> = _states

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        if (store.load().isEmpty()) store.save(_routers.value)
        refresh()
    }

    /** First run: scan the Wi-Fi network the phone is on, no setup needed. */
    private fun defaultRouters(): List<RouterConfig> {
        val ip = ScanDriver.localIps().firstOrNull() ?: return emptyList()
        val gateway = ip.substringBeforeLast('.') + ".1"
        return listOf(RouterConfig("local", "شبكتي", gateway, RouterType.SCAN, "", ""))
    }

    fun rename(key: String, name: String) {
        _names.update { if (name.isBlank()) it - key else it + (key to name.trim()) }
        notes.saveNames(_names.value)
    }

    /** Marks every device currently listed as known, which clears the "جديد" badges. */
    fun acceptAll() {
        val keys = _states.value.values.flatMap { (it as? RouterState.Ok)?.clients.orEmpty() }.map { it.mac }.toSet()
        _known.value = (_known.value.orEmpty() + keys)
        notes.saveKnown(_known.value!!)
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Queries all routers in parallel; one slow or dead router never blocks the others. */
    fun refresh() {
        val list = _routers.value
        _states.value = list.associate { it.id to RouterState.Loading }
        viewModelScope.launch {
            list.map { router -> async { loadOne(router) } }.awaitAll()
        }
    }

    private suspend fun loadOne(router: RouterConfig) {
        val state = withContext(Dispatchers.IO) {
            runCatching { RouterState.Ok(router.driver().clients()) }
                .getOrElse { RouterState.Failed(it.message ?: it.javaClass.simpleName) }
        }
        _states.update { it + (router.id to state) }
        if (_known.value == null && state is RouterState.Ok && state.clients.isNotEmpty()) acceptAll()
    }

    fun perform(action: Action, client: Client) {
        val router = _routers.value.firstOrNull { it.id == client.routerId } ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val d = router.driver()
                    when (action) {
                        Action.BLOCK -> d.block(client.mac)
                        Action.UNBLOCK -> d.unblock(client.mac)
                        Action.KICK -> d.kick(client.mac)
                    }
                }
            }
            _message.value = result.fold(
                onSuccess = { "تم: ${action.label}" },
                onFailure = { "فشل: ${it.message ?: it.javaClass.simpleName}" },
            )
            loadOne(router)
        }
    }

    fun saveRouter(router: RouterConfig) {
        val isNew = _routers.value.none { it.id == router.id }
        _routers.update { list -> if (isNew) list + router else list.map { if (it.id == router.id) router else it } }
        store.save(_routers.value)
        viewModelScope.launch { loadOne(router) }
    }

    fun deleteRouter(id: String) {
        _routers.update { list -> list.filterNot { it.id == id } }
        _states.update { it - id }
        store.save(_routers.value)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
