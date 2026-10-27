package com.salahfocus.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.salahfocus.app.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppInfo(val pkg: String, val label: String)

@Composable
fun AppsScreen(prefs: Prefs, onChanged: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selected by remember { mutableStateOf(prefs.blockedApps) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
        }
    }

    // Selected apps first, then alphabetical; recomputed only when the list or selection changes.
    val sorted = remember(apps, selected) { apps.sortedWith(compareBy({ it.pkg !in selected }, { it.label })) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "اختر البرامج التي تريد حجبها وقت الصلاة (${selected.count { s -> apps.any { it.pkg == s } }} مختارة)",
            modifier = Modifier.padding(vertical = 16.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(sorted, key = { it.pkg }) { app ->
                val checked = app.pkg in selected
                val toggle = {
                    selected = if (checked) selected - app.pkg else selected + app.pkg
                    prefs.blockedApps = selected
                    onChanged()
                }
                Row(
                    Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, { toggle() })
                    Text(app.label)
                }
            }
        }
    }
}
