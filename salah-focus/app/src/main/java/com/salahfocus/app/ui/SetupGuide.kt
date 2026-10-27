package com.salahfocus.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private class SetupAction(val label: String, val primary: Boolean = true, val run: () -> Unit)

private class SetupStep(val title: String, val help: String, val done: Boolean, val actions: List<SetupAction>)

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Step-by-step checklist of the phone settings the blocker needs. Each step opens the exact
 * system screen. It disappears once every step is done. Statuses are re-read on every recomposition.
 */
@Composable
fun SetupGuide() {
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pkg = context.packageName
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    val steps = buildList {
        add(
            SetupStep(
                "تفعيل خدمة الحجب",
                "افتح إمكانية الوصول واختر «وقت الصلاة» ثم فعّله. إذا ظهرت رسالة «تم منع التطبيق من الوصول» فاضغط الزر الثاني، ثم النقاط الثلاث ⋮ في الأعلى، ثم «السماح بالإعدادات المقيّدة»، وارجع لتفعيل الخدمة.",
                isBlockerEnabled(context),
                listOf(
                    SetupAction("فتح إمكانية الوصول") { openAccessibilitySettings(context) },
                    SetupAction("السماح بالإعدادات المقيّدة", primary = false) { open(context, Intent(appDetails)) },
                ),
            ),
        )
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        add(
            SetupStep(
                "منع الجوال من إيقاف التطبيق",
                "اسمح للتطبيق بالعمل بدون قيود البطارية، وإلا قد يوقف الجوال خدمة الحجب بعد فترة.",
                pm.isIgnoringBatteryOptimizations(pkg),
                listOf(
                    SetupAction("السماح") {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    },
                ),
            ),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                SetupStep(
                    "إشعارات التذكير بالصلاة",
                    "للتنبيه قبل الصلاة وعند دخول وقتها وبعدها.",
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    listOf(SetupAction("السماح بالإشعارات") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }),
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            add(
                SetupStep(
                    "المنبّهات الدقيقة",
                    "ليصل التذكير في الدقيقة المحددة بالضبط.",
                    am.canScheduleExactAlarms(),
                    listOf(
                        SetupAction("السماح") {
                            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
                        },
                    ),
                ),
            )
        }
    }

    val doneCount = steps.count { it.done }
    if (doneCount == steps.size) return

    SectionCard("إعداد سريع ($doneCount من ${steps.size})") {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { doneCount.toFloat() / steps.size }, modifier = Modifier.fillMaxWidth())
            steps.forEach { step ->
                if (step.done) {
                    Text("✅ ${step.title}")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⬜ ${step.title}", fontWeight = FontWeight.Bold)
                        Text(step.help, style = MaterialTheme.typography.bodySmall)
                        step.actions.forEach { a ->
                            if (a.primary) Button(onClick = a.run) { Text(a.label) }
                            else OutlinedButton(onClick = a.run) { Text(a.label) }
                        }
                    }
                }
            }
            Text(
                "إذا ظهر التطبيق المحجوب ولم تظهر شاشة الحجب، افتح معلومات التطبيق وفعّل «النوافذ المنبثقة في الخلفية» (جوالات شاومي وأوبو وريلمي).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
