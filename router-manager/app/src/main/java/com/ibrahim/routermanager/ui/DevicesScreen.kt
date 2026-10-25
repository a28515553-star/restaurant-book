package com.ibrahim.routermanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ibrahim.routermanager.data.Client
import com.ibrahim.routermanager.data.RouterConfig
import com.ibrahim.routermanager.data.RouterState
import com.ibrahim.routermanager.data.RouterType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    routers: List<RouterConfig>,
    states: Map<String, RouterState>,
    onRefresh: () -> Unit,
    onAction: (Action, Client) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<Action, Client>?>(null) }
    val loading = states.values.any { it is RouterState.Loading }
    val total = states.values.sumOf { (it as? RouterState.Ok)?.clients?.size ?: 0 }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("بحث بالاسم أو IP أو MAC") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "إجمالي الأجهزة: $total",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (routers.isEmpty()) {
                Text("أضف راوتراً من تبويب «الراوترات» أولاً", modifier = Modifier.padding(24.dp))
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                routers.forEach { router ->
                    item(key = "h-${router.id}") { RouterHeader(router, states[router.id]) }
                    val state = states[router.id]
                    if (state is RouterState.Ok) {
                        val shown = state.clients.filter { it.matches(query) }
                            .sortedWith(compareBy({ !it.blocked }, { it.ip.padIpv4() }))
                        items(shown, key = { "${router.id}-${it.mac}" }) { c ->
                            ClientRow(c, router.type.canControl) { a -> pending = a to c }
                        }
                    }
                }
            }
        }
    }

    pending?.let { (action, client) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(action.label) },
            text = { Text("${client.title()}\n${client.mac}") },
            confirmButton = {
                TextButton(onClick = { onAction(action, client); pending = null }) { Text("تأكيد") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun RouterHeader(router: RouterConfig, state: RouterState?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${router.name}  (${router.host})", fontWeight = FontWeight.Bold)
            when (state) {
                is RouterState.Failed -> Text(state.message, color = BlockedRed, style = MaterialTheme.typography.bodySmall)
                is RouterState.Ok -> Text("${state.clients.size} جهاز", style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
        }
        if (state == null || state is RouterState.Loading) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
    }
    HorizontalDivider()
}

@Composable
private fun ClientRow(client: Client, canControl: Boolean, onAction: (Action) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(client.title(), fontWeight = FontWeight.SemiBold)
            Text(
                listOf(client.ip, client.mac.takeIf { canControl }).filterNotNull().filter { it.isNotEmpty() }.joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (client.blocked) Text("محظور", color = BlockedRed, fontWeight = FontWeight.Bold)
            if (canControl) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (client.blocked) {
                        OutlinedButton(onClick = { onAction(Action.UNBLOCK) }) { Text(Action.UNBLOCK.label) }
                    } else {
                        OutlinedButton(onClick = { onAction(Action.BLOCK) }) { Text(Action.BLOCK.label) }
                        OutlinedButton(onClick = { onAction(Action.KICK) }) { Text(Action.KICK.label) }
                    }
                }
            }
        }
    }
}

private fun Client.title() = name.ifEmpty { ip.ifEmpty { mac } }

private fun Client.matches(q: String) =
    q.isBlank() || listOf(name, ip, mac).any { it.contains(q.trim(), ignoreCase = true) }

/** Sort key so 192.168.1.9 comes before 192.168.1.10. */
private fun String.padIpv4() = split('.').joinToString(".") { it.padStart(3, '0') }
