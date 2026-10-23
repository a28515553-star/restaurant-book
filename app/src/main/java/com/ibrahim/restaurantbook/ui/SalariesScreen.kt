package com.ibrahim.restaurantbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import com.ibrahim.restaurantbook.data.Salary
import java.time.LocalDate

@Composable
fun SalariesScreen(vm: MainViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val salaries by vm.salaries.collectAsStateWithLifecycle()
    val names by vm.employeeNames.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Salary?>(null) }
    var deleting by remember { mutableStateOf<Salary?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp)) {
            item { TotalBanner("رواتب الشهر", salaries.sumOf { it.amount }, SalaryBlue) }
            if (salaries.isEmpty()) {
                item { EmptyState("لا توجد رواتب مسجلة في هذا الشهر.") }
            }
            items(salaries, key = { it.id }) { s ->
                EntryRow(
                    title = s.employeeName,
                    subtitle = listOf(formatDay(s.date), s.note).filter { it.isNotBlank() }.joinToString(" · "),
                    amount = s.amount,
                    amountColor = SalaryBlue,
                    onClick = { editing = s },
                    onDelete = { deleting = s },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Salary(employeeName = "", amount = 0.0, date = defaultDateFor(month).toEpochDay()) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("إضافة راتب") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { s ->
        SalaryDialog(s, names, onDismiss = { editing = null }) { name, amount, date, note ->
            vm.saveSalary(s.id, name, amount, date, note)
            editing = null
        }
    }
    deleting?.let { s ->
        ConfirmDeleteDialog(
            message = "حذف راتب ${s.employeeName} بمبلغ ${formatAmount(s.amount)}؟",
            onConfirm = { vm.deleteSalary(s) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SalaryDialog(
    initial: Salary,
    knownNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double, date: LocalDate, note: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial.employeeName) }
    var amountText by remember { mutableStateOf(if (initial.id == 0L) "" else initial.amount.toInputText()) }
    var date by remember { mutableStateOf(LocalDate.ofEpochDay(initial.date)) }
    var note by remember { mutableStateOf(initial.note) }
    var showErrors by remember { mutableStateOf(false) }
    val amount = parseAmount(amountText)

    EntryDialog(
        title = if (initial.id == 0L) "راتب جديد" else "تعديل راتب",
        onDismiss = onDismiss,
        onSave = {
            if (amount == null || name.isBlank()) showErrors = true
            else onSave(name, amount, date, note)
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("اسم الموظف") },
            singleLine = true,
            isError = showErrors && name.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (knownNames.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(knownNames) { n ->
                    SuggestionChip(onClick = { name = n }, label = { Text(n) })
                }
            }
        }
        AmountField(amountText, { amountText = it }, isError = showErrors && amount == null)
        DateField(date) { date = it }
        NoteField(note) { note = it }
    }
}
