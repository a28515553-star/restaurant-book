package com.ibrahim.restaurantbook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibrahim.restaurantbook.data.AppDatabase
import com.ibrahim.restaurantbook.data.Category
import com.ibrahim.restaurantbook.data.CategoryTotal
import com.ibrahim.restaurantbook.data.Expense
import com.ibrahim.restaurantbook.data.ExpenseWithCategory
import com.ibrahim.restaurantbook.data.Income
import com.ibrahim.restaurantbook.data.NameTotal
import com.ibrahim.restaurantbook.data.Salary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()
    private val salaryDao = db.salaryDao()
    private val incomeDao = db.incomeDao()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private fun <T> perMonth(initial: T, query: (start: Long, end: Long) -> Flow<T>): StateFlow<T> =
        _month.flatMapLatest { m -> query(m.atDay(1).toEpochDay(), m.atEndOfMonth().toEpochDay()) }
            .state(initial)

    val categories: StateFlow<List<Category>> = categoryDao.observeAll().state(emptyList())
    val employeeNames: StateFlow<List<String>> = salaryDao.observeEmployeeNames().state(emptyList())

    val expenses: StateFlow<List<ExpenseWithCategory>> = perMonth(emptyList(), expenseDao::observeRange)
    val categoryTotals: StateFlow<List<CategoryTotal>> = perMonth(emptyList(), expenseDao::observeTotalsByCategory)
    val salaries: StateFlow<List<Salary>> = perMonth(emptyList(), salaryDao::observeRange)
    val salaryTotals: StateFlow<List<NameTotal>> = perMonth(emptyList(), salaryDao::observeTotalsByEmployee)
    val incomes: StateFlow<List<Income>> = perMonth(emptyList(), incomeDao::observeRange)

    fun previousMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    fun saveExpense(id: Long, categoryId: Long, amount: Double, date: LocalDate, note: String) =
        viewModelScope.launch {
            val e = Expense(id, categoryId, amount, date.toEpochDay(), note.trim())
            if (id == 0L) expenseDao.insert(e) else expenseDao.update(e)
        }

    fun deleteExpense(id: Long) = viewModelScope.launch { expenseDao.deleteById(id) }

    /** Returns the id of the category with this name, creating it if needed. */
    fun addCategory(name: String, onDone: (Long) -> Unit) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        val existing = categoryDao.findByName(clean)
        onDone(existing?.id ?: categoryDao.insert(Category(name = clean)))
    }

    /** Only unused categories can be deleted, so old expenses keep their category. */
    fun deleteCategory(category: Category, onResult: (deleted: Boolean) -> Unit) = viewModelScope.launch {
        if (categoryDao.usageCount(category.id) > 0) {
            onResult(false)
        } else {
            categoryDao.delete(category)
            onResult(true)
        }
    }

    fun saveSalary(id: Long, employee: String, amount: Double, date: LocalDate, note: String) =
        viewModelScope.launch {
            val s = Salary(id, employee.trim(), amount, date.toEpochDay(), note.trim())
            if (id == 0L) salaryDao.insert(s) else salaryDao.update(s)
        }

    fun deleteSalary(salary: Salary) = viewModelScope.launch { salaryDao.delete(salary) }

    fun saveIncome(id: Long, amount: Double, date: LocalDate, note: String) =
        viewModelScope.launch {
            val i = Income(id, amount, date.toEpochDay(), note.trim())
            if (id == 0L) incomeDao.insert(i) else incomeDao.update(i)
        }

    fun deleteIncome(income: Income) = viewModelScope.launch { incomeDao.delete(income) }
}
