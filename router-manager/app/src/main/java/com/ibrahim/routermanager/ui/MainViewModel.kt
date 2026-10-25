package com.ibrahim.routermanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
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

    private val _routers = MutableStateFlow(store.load())
    val routers: StateFlow<List<RouterConfig>> = _routers

    private val _states = MutableStateFlow<Map<String, RouterState>>(emptyMap())
    val states: StateFlow<Map<String, RouterState>> = _states

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        refresh()
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
