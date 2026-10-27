package com.salahfocus.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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
import androidx.core.content.ContextCompat
import com.salahfocus.app.core.CITIES
import com.salahfocus.app.core.METHODS
import com.salahfocus.app.core.Prefs
import java.util.TimeZone

@Composable
fun SettingsScreen(prefs: Prefs, rev: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var cityPickerOpen by remember { mutableStateOf(false) }
    var gpsFailed by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && useDeviceLocation(context, prefs)) {
            gpsFailed = false
            onChanged()
        } else gpsFailed = true
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
            Text("الموقع الحالي: ${prefs.placeName}", modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { cityPickerOpen = !cityPickerOpen }) { Text("اختيار مدينة") }
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        if (useDeviceLocation(context, prefs)) { gpsFailed = false; onChanged() } else gpsFailed = true
                    } else locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }) { Text("موقعي الحالي") }
            }
            if (gpsFailed) Text("تعذّر تحديد موقعك، اختر مدينة يدوياً.", modifier = Modifier.padding(top = 8.dp))
            if (cityPickerOpen) {
                CITIES.forEach { c ->
                    Text(
                        c.ar,
                        modifier = Modifier.fillMaxWidth().clickable {
                            prefs.setPlace(c.ar, c.lat, c.lng, c.tz)
                            cityPickerOpen = false
                            onChanged()
                        }.padding(vertical = 10.dp),
                    )
                }
            }
        }

        SectionCard("طريقة الحساب") {
            METHODS.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { prefs.methodId = m.id; onChanged() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(prefs.methodId == m.id, { prefs.methodId = m.id; onChanged() })
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

/** Uses the last known location (no continuous tracking). Returns false if none is available. */
@SuppressLint("MissingPermission")
private fun useDeviceLocation(context: Context, prefs: Prefs): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time } ?: return false
    prefs.setPlace("موقعي الحالي", loc.latitude, loc.longitude, TimeZone.getDefault().id)
    return true
}
