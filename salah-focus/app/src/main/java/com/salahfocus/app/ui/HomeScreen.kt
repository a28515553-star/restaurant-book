package com.salahfocus.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salahfocus.app.core.LocationHelper
import com.salahfocus.app.core.Prefs
import com.salahfocus.app.core.Schedule
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(prefs: Prefs, rev: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val tz = prefs.tzId
    // rev is read so this recomposes (and recalculates) when a setting changes.
    val active = remember(rev, now / 1000) { Schedule.activeEvent(prefs, now) }
    val next = remember(rev, now / 1000) { Schedule.nextPrayer(prefs, now) }
    val today = remember(rev, now / 60_000) { Schedule.today(prefs, now) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LocationHelper.refresh(context, prefs) { if (it) onChanged() }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val blockerOn = isBlockerEnabled(context)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Teal).padding(24.dp),
        ) {
            if (active != null) {
                Text("برامج التواصل محجوبة الآن", color = Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("وقت صلاة ${active.prayer.ar}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "تُفتح بعد ${formatCountdown(active.end - now)} (${formatTime(active.end, tz)})",
                    color = Color.White.copy(alpha = 0.8f),
                )
            } else if (next != null) {
                Text("الصلاة القادمة", color = Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${next.prayer.ar}  ${formatTime(next.time, tz)}",
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                )
                Text(formatCountdown(next.time - now), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
                Text(
                    "يبدأ الحجب عند ${formatTime(next.start, tz)}",
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(prefs.placeName, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }

        if (!prefs.hasLocation) {
            SectionCard("حدد موقعك") {
                Text(
                    "لحساب مواقيت الصلاة بدقة في أي مكان بالعالم، حدد موقعك تلقائياً أو اختر مدينتك من الإعدادات.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("تحديد موقعي تلقائياً") }
            }
        }

        if (!blockerOn) {
            SectionCard("فعّل الحجب") {
                Text(
                    "لكي يعمل الحجب، فعّل «وقت الصلاة» من إعدادات إمكانية الوصول. التطبيق لا يقرأ محتوى الشاشة، فقط يعرف اسم التطبيق المفتوح.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { openAccessibilitySettings(context) }) { Text("فتح إعدادات إمكانية الوصول") }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && prefs.remindersEnabled) {
            Button(
                onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("السماح بإشعارات التذكير") }
        }

        SectionCard("مواقيت اليوم") {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                today.forEach { e ->
                    val isNext = next != null && e.time == next.time
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isNext) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(e.prayer.ar, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                "الحجب: ${formatTime(e.start, tz)} – ${formatTime(e.end, tz)}",
                                fontSize = 12.sp, color = Color.Gray,
                            )
                        }
                        Text(formatTime(e.time, tz), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
