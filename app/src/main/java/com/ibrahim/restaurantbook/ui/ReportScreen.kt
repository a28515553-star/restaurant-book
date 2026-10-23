package com.ibrahim.restaurantbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

@Composable
fun ReportScreen(vm: MainViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val categoryTotals by vm.categoryTotals.collectAsStateWithLifecycle()
    val salaryTotals by vm.salaryTotals.collectAsStateWithLifecycle()
    val incomes by vm.incomes.collectAsStateWithLifecycle()

    val totalIncome = incomes.sumOf { it.amount }
    val totalExpenses = categoryTotals.sumOf { it.total }
    val totalSalaries = salaryTotals.sumOf { it.total }
    val totalCosts = totalExpenses + totalSalaries
    val net = totalIncome - totalCosts

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResultCard(net, formatMonth(month))

        SectionCard("الحساب") {
            Line("إجمالي الدخل", totalIncome, ProfitGreen, bold = true)
            Line("إجمالي المصروفات", totalExpenses, ExpenseOrange)
            Line("إجمالي الرواتب", totalSalaries, SalaryBlue)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Line("مجموع التكاليف (مصروفات + رواتب)", totalCosts, ExpenseOrange, bold = true)
            Line(if (net >= 0) "صافي الربح" else "صافي الخسارة", abs(net), if (net >= 0) ProfitGreen else LossRed, bold = true)
        }

        SectionCard("المصروفات حسب النوع") {
            if (categoryTotals.isEmpty()) {
                Text("لا توجد مصروفات", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            categoryTotals.forEach { c ->
                ShareLine(c.name, c.total, totalExpenses, ExpenseOrange)
            }
        }

        SectionCard("الرواتب حسب الموظف") {
            if (salaryTotals.isEmpty()) {
                Text("لا توجد رواتب", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            salaryTotals.forEach { s ->
                ShareLine(s.name, s.total, totalSalaries, SalaryBlue)
            }
        }
    }
}

@Composable
private fun ResultCard(net: Double, monthLabel: String) {
    val color = if (net >= 0) ProfitGreen else LossRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("نتيجة شهر $monthLabel", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                if (net >= 0) "ربح" else "خسارة",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formatAmount(abs(net)),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Line(label: String, amount: Double, color: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null)
        Text(formatAmount(amount), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShareLine(label: String, amount: Double, total: Double, color: Color) {
    val share = if (total > 0) (amount / total).toFloat() else 0f
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Text("${formatAmount(amount)}  (${(share * 100).toInt()}%)", color = color, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
    }
}
