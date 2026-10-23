package com.ibrahim.restaurantbook.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Dates are stored as LocalDate.toEpochDay() so month ranges are simple BETWEEN queries. */

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId"), Index("date")],
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val amount: Double,
    val date: Long,
    val note: String = "",
)

@Entity(tableName = "salaries", indices = [Index("date")])
data class Salary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeName: String,
    val amount: Double,
    val date: Long,
    val note: String = "",
)

@Entity(tableName = "incomes", indices = [Index("date")])
data class Income(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val date: Long,
    val note: String = "",
)

data class ExpenseWithCategory(
    val id: Long,
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,
    val date: Long,
    val note: String,
)

data class CategoryTotal(
    val name: String,
    val total: Double,
)

data class NameTotal(
    val name: String,
    val total: Double,
)
