package com.ibrahim.restaurantbook.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ibrahim.restaurantbook.data.Category
import com.ibrahim.restaurantbook.data.ExpenseWithCategory
import java.time.LocalDate

private const val NEW_CATEGORY_ID = -1L

@Composable
fun ExpensesScreen(vm: MainViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    // null = closed, id 0 = new entry
    var editing by remember { mutableStateOf<ExpenseWithCategory?>(null) }
    var deleting by remember { mutableStateOf<ExpenseWithCategory?>(null) }
    var managingCategories by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp)) {
            item {
                TotalBanner("مصروفات الشهر", expenses.sumOf { it.amount }, ExpenseOrange)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { managingCategories = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Text("  الفئات")
                    }
                }
            }
            if (expenses.isEmpty()) {
                item { EmptyState("لا توجد مصروفات في هذا الشهر.\nاضغط \"إضافة مصروف\" للبدء.") }
            }
            expenses.groupBy { it.date }.forEach { (day, list) ->
                item(key = "day-$day") { DayHeader(day, list.sumOf { it.amount }) }
                items(list, key = { it.id }) { e ->
                    EntryRow(
                        title = e.categoryName,
                        subtitle = e.note,
                        amount = e.amount,
                        amountColor = ExpenseOrange,
                        onClick = { editing = e },
                        onDelete = { deleting = e },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                editing = ExpenseWithCategory(
                    id = 0,
                    categoryId = categories.firstOrNull()?.id ?: NEW_CATEGORY_ID,
                    categoryName = "",
                    amount = 0.0,
                    date = defaultDateFor(month).toEpochDay(),
                    note = "",
                )
            },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("إضافة مصروف") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { e ->
        ExpenseDialog(
            initial = e,
            categories = categories,
            onDismiss = { editing = null },
            onSave = { categoryId, newCategoryName, amount, date, note ->
                if (categoryId == NEW_CATEGORY_ID) {
                    vm.addCategory(newCategoryName) { id -> vm.saveExpense(e.id, id, amount, date, note) }
                } else {
                    vm.saveExpense(e.id, categoryId, amount, date, note)
                }
                editing = null
            },
        )
    }

    deleting?.let { e ->
        ConfirmDeleteDialog(
            message = "حذف مصروف ${e.categoryName} بمبلغ ${formatAmount(e.amount)}؟",
            onConfirm = { vm.deleteExpense(e.id) },
            onDismiss = { deleting = null },
        )
    }

    if (managingCategories) {
        CategoriesDialog(vm, categories, onDismiss = { managingCategories = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDialog(
    initial: ExpenseWithCategory,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (categoryId: Long, newCategoryName: String, amount: Double, date: LocalDate, note: String) -> Unit,
) {
    var categoryId by remember { mutableStateOf(initial.categoryId) }
    var newCategoryName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf(if (initial.id == 0L) "" else initial.amount.toInputText()) }
    var date by remember { mutableStateOf(LocalDate.ofEpochDay(initial.date)) }
    var note by remember { mutableStateOf(initial.note) }
    var expanded by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    val amount = parseAmount(amountText)
    val selectedName = if (categoryId == NEW_CATEGORY_ID) "فئة جديدة…"
    else categories.firstOrNull { it.id == categoryId }?.name ?: ""
    val newNameMissing = categoryId == NEW_CATEGORY_ID && newCategoryName.isBlank()

    EntryDialog(
        title = if (initial.id == 0L) "مصروف جديد" else "تعديل مصروف",
        onDismiss = onDismiss,
        onSave = {
            if (amount == null || newNameMissing) showErrors = true
            else onSave(categoryId, newCategoryName, amount, date, note)
        },
    ) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                label = { Text("نوع المصروف") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                categories.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { categoryId = c.id; expanded = false })
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("➕ فئة جديدة…") },
                    onClick = { categoryId = NEW_CATEGORY_ID; expanded = false },
                )
            }
        }
        if (categoryId == NEW_CATEGORY_ID) {
            OutlinedTextField(
                value = newCategoryName,
                onValueChange = { newCategoryName = it },
                label = { Text("اسم الفئة الجديدة") },
                singleLine = true,
                isError = showErrors && newNameMissing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AmountField(amountText, { amountText = it }, isError = showErrors && amount == null)
        DateField(date) { date = it }
        NoteField(note) { note = it }
    }
}

@Composable
private fun CategoriesDialog(vm: MainViewModel, categories: List<Category>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فئات المصروفات") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                categories.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            vm.deleteCategory(c) { deleted ->
                                if (!deleted) {
                                    Toast.makeText(
                                        context,
                                        "لا يمكن حذف \"${c.name}\" لأن عليها مصروفات مسجلة",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف ${c.name}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("فئة جديدة") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        vm.addCategory(newName) {}
                        newName = ""
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "إضافة")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}
