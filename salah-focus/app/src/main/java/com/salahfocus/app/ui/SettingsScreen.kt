package com.salahfocus.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.salahfocus.app.core.CITIES
import com.salahfocus.app.core.LocationHelper
import com.salahfocus.app.core.METHODS
import com.salahfocus.app.core.Prefs

@Composable
fun SettingsScreen(prefs: Prefs, rev: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var cityPickerOpen by remember { mutableStateOf(false) }
    var gpsFailed by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    val findLocation = {
        LocationHelper.refresh(context, prefs) { ok ->
            gpsFailed = !ok
            if (ok) onChanged()
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) findLocation() else gpsFailed = true
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("الحجب") {
            SwitchRow("تفعيل حجب برامج التواصل", prefs.blockingEnabled) { prefs.blockingEnabled = it; onChanged() }
            Text("الحجب قبل الصلاة: ${prefs.minutesBefore} دقيقة")
            Slider(
                value = prefs.minutesBefore.toFloat(), onValueChange = { prefs.minutesBefore = it.toInt(); onChanged() },
                valueRange = 0f..30f, steps = 29,
            )
            Text("الفتح بعد الصلاة: ${prefs.minutesAfter} دقيقة")
            Slider(
                value = prefs.minutesAfter.toFloat(), onValueChange = { prefs.minutesAfter = it.toInt(); onChanged() },
                valueRange = 0f..30f, steps = 29,
            )
        }

        SectionCard("التذكير") {
            SwitchRow("إشعارات قبل الصلاة وعند الأذان وبعدها", prefs.remindersEnabled) { prefs.remindersEnabled = it; onChanged() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canExact(context)) {
                Text("لضمان وصول التذكير في وقته بالضبط اسمح بالمنبّهات الدقيقة.", modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("السماح بالمنبّهات الدقيقة") }
            }
        }

        SectionCard("الموقع") {
            Text(
                "الموقع الحالي: ${prefs.placeName}" + if (prefs.autoLocation) " (تلقائي، يتبع منطقة جوالك الزمنية)" else "",
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (LocationHelper.hasPermission(context)) findLocation()
                    else locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }) { Text("تحديد موقعي تلقائياً") }
                OutlinedButton(onClick = { cityPickerOpen = !cityPickerOpen }) { Text("اختيار مدينة") }
            }
            if (gpsFailed) Text("تعذّر تحديد موقعك، فعّل الموقع في الجوال أو اختر مدينة يدوياً.", modifier = Modifier.padding(top = 8.dp))
            if (cityPickerOpen) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("ابحث عن مدينة") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                val shown = CITIES.filter { query.isBlank() || it.ar.contains(query.trim()) }
                shown.forEach { c ->
                    Text(
                        c.ar,
                        modifier = Modifier.fillMaxWidth().clickable {
                            prefs.setCity(c)
                            cityPickerOpen = false
                            query = ""
                            onChanged()
                        }.padding(vertical = 10.dp),
                    )
                }
                if (shown.isEmpty()) Text("لا توجد نتائج. جرّب «تحديد موقعي تلقائياً» لأي مكان في العالم.", modifier = Modifier.padding(top = 8.dp))
            }
        }

        SectionCard("طريقة الحساب") {
            METHODS.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { prefs.chooseMethod(m.id); onChanged() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(prefs.methodId == m.id, { prefs.chooseMethod(m.id); onChanged() })
                    Text(m.ar)
                }
            }
            Text("وقت العصر", modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(prefs.asrFactor == 1, { prefs.asrFactor = 1; onChanged() })
                Text("جمهور (الظل مثله)")
                RadioButton(prefs.asrFactor == 2, { prefs.asrFactor = 2; onChanged() })
                Text("حنفي (مثلاه)")
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

private fun canExact(context: Context): Boolean =
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
