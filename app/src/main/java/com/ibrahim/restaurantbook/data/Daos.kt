package com.ibrahim.restaurantbook.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id")
    fun observeAll(): Flow<List<Category>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Category?

    @Query("SELECT COUNT(*) FROM expenses WHERE categoryId = :categoryId")
    suspend fun usageCount(categoryId: Long): Int

    @Delete
    suspend fun delete(category: Category)
}

@Dao
interface ExpenseDao {
    @Query(
        """
        SELECT e.id, e.categoryId, c.name AS categoryName, e.amount, e.date, e.note
        FROM expenses e JOIN categories c ON c.id = e.categoryId
        WHERE e.date BETWEEN :start AND :end
        ORDER BY e.date DESC, e.id DESC
        """
    )
    fun observeRange(start: Long, end: Long): Flow<List<ExpenseWithCategory>>

    @Query(
        """
        SELECT c.name AS name, SUM(e.amount) AS total
        FROM expenses e JOIN categories c ON c.id = e.categoryId
        WHERE e.date BETWEEN :start AND :end
        GROUP BY c.id
        ORDER BY total DESC
        """
    )
    fun observeTotalsByCategory(start: Long, end: Long): Flow<List<CategoryTotal>>

    @Insert
    suspend fun insert(expense: Expense)

    @Update
    suspend fun update(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SalaryDao {
    @Query("SELECT * FROM salaries WHERE date BETWEEN :start AND :end ORDER BY date DESC, id DESC")
    fun observeRange(start: Long, end: Long): Flow<List<Salary>>

    @Query(
        """
        SELECT employeeName AS name, SUM(amount) AS total FROM salaries
        WHERE date BETWEEN :start AND :end
        GROUP BY employeeName ORDER BY total DESC
        """
    )
    fun observeTotalsByEmployee(start: Long, end: Long): Flow<List<NameTotal>>

    @Query("SELECT DISTINCT employeeName FROM salaries ORDER BY employeeName")
    fun observeEmployeeNames(): Flow<List<String>>

    @Insert
    suspend fun insert(salary: Salary)

    @Update
    suspend fun update(salary: Salary)

    @Delete
    suspend fun delete(salary: Salary)
}

@Dao
interface IncomeDao {
    @Query("SELECT * FROM incomes WHERE date BETWEEN :start AND :end ORDER BY date DESC, id DESC")
    fun observeRange(start: Long, end: Long): Flow<List<Income>>

    @Insert
    suspend fun insert(income: Income)

    @Update
    suspend fun update(income: Income)

    @Delete
    suspend fun delete(income: Income)
}
