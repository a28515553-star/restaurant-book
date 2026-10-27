package com.salahfocus.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salahfocus.app.core.Prayer
import com.salahfocus.app.ui.Gold
import com.salahfocus.app.ui.SalahTheme
import com.salahfocus.app.ui.Teal
import com.salahfocus.app.ui.formatCountdown
import kotlinx.coroutines.delay

/** Shown on top of a blocked social app until the block window ends. */
class BlockActivity : ComponentActivity() {
    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prayer = intent.getStringExtra("prayer")?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        val end = intent.getLongExtra("end", 0L)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            SalahTheme {
                BlockScreen(prayer?.ar ?: "", end, onHome = ::goHome, onDone = ::finish)
            }
        }
    }
}

@Composable
private fun BlockScreen(prayerName: String, end: Long, onHome: () -> Unit, onDone: () -> Unit) {
    var left by remember { mutableLongStateOf(end - System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (true) {
            left = end - System.currentTimeMillis()
            if (left <= 0) {
                onDone()
                break
            }
            delay(500)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Teal).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🕌", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text("وقت صلاة $prayerName", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("برامج التواصل محجوبة الآن", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(32.dp))
        Text(formatCountdown(left), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
        Text("حتى تُفتح من جديد", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))
        Text(
            "﴿إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَّوْقُوتًا﴾",
            color = Gold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onHome,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Teal),
        ) { Text("العودة للشاشة الرئيسية", fontSize = 16.sp) }
    }
}
