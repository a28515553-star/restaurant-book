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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ibrahim.routermanager.data.RouterConfig
import com.ibrahim.routermanager.data.RouterType

@Composable
fun RoutersScreen(
    routers: List<RouterConfig>,
    newId: () -> String,
    onSave: (RouterConfig) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<RouterConfig?>(null) }

    Column(modifier.fillMaxSize()) {
        Button(
            onClick = { editing = RouterConfig(newId(), "", "192.168.1.", RouterType.MIKROTIK, "admin", "") },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("إضافة راوتر") }

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(routers, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            Text("${r.host} • ${r.type.label}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { editing = r }) { Text("تعديل") }
                        TextButton(onClick = { onDelete(r.id) }) { Text("حذف", color = BlockedRed) }
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        RouterDialog(
            initial = initial,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
        )
    }
}

@Composable
private fun RouterDialog(initial: RouterConfig, onDismiss: () -> Unit, onSave: (RouterConfig) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var type by remember { mutableStateOf(initial.type) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    val valid = name.isNotBlank() && host.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("بيانات الراوتر") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم (مثلاً: الطابق الأول)") }, singleLine = true)
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("عنوان IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                RouterType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = type == t, onClick = { type = t }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (type.canControl) {
                    OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(initial.copy(name = name.trim(), host = host, type = type, username = user, password = pass)) },
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
