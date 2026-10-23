package com.ibrahim.restaurantbook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ibrahim.restaurantbook.data.Income
import java.time.LocalDate

@Composable
fun IncomeScreen(vm: MainViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val incomes by vm.incomes.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Income?>(null) }
    var deleting by remember { mutableStateOf<Income?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp)) {
            item { TotalBanner("دخل الشهر", incomes.sumOf { it.amount }, ProfitGreen) }
            if (incomes.isEmpty()) {
                item { EmptyState("لا يوجد دخل مسجل في هذا الشهر.\nسجّل دخل كل يوم من هنا.") }
            }
            items(incomes, key = { it.id }) { i ->
                EntryRow(
                    title = formatDay(i.date),
                    subtitle = i.note,
                    amount = i.amount,
                    amountColor = ProfitGreen,
                    onClick = { editing = i },
                    onDelete = { deleting = i },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Income(amount = 0.0, date = defaultDateFor(month).toEpochDay()) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("إضافة دخل") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { i ->
        IncomeDialog(i, onDismiss = { editing = null }) { amount, date, note ->
            vm.saveIncome(i.id, amount, date, note)
            editing = null
        }
    }
    deleting?.let { i ->
        ConfirmDeleteDialog(
            message = "حذف دخل يوم ${formatDay(i.date)} بمبلغ ${formatAmount(i.amount)}؟",
            onConfirm = { vm.deleteIncome(i) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun IncomeDialog(
    initial: Income,
    onDismiss: () -> Unit,
    onSave: (amount: Double, date: LocalDate, note: String) -> Unit,
) {
    var amountText by remember { mutableStateOf(if (initial.id == 0L) "" else initial.amount.toInputText()) }
    var date by remember { mutableStateOf(LocalDate.ofEpochDay(initial.date)) }
    var note by remember { mutableStateOf(initial.note) }
    var showErrors by remember { mutableStateOf(false) }
    val amount = parseAmount(amountText)

    EntryDialog(
        title = if (initial.id == 0L) "دخل جديد" else "تعديل دخل",
        onDismiss = onDismiss,
        onSave = {
            if (amount == null) showErrors = true else onSave(amount, date, note)
        },
    ) {
        AmountField(amountText, { amountText = it }, isError = showErrors && amount == null)
        DateField(date) { date = it }
        NoteField(note) { note = it }
    }
}
