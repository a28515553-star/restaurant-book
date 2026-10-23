package com.ibrahim.restaurantbook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Category::class, Expense::class, Salary::class, Income::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun salaryDao(): SalaryDao
    abstract fun incomeDao(): IncomeDao

    companion object {
        private val DEFAULT_CATEGORIES = listOf(
            "غاز", "خضار وفواكه", "لحوم ودجاج", "مواد غذائية", "خبز",
            "كهرباء", "ماء", "إيجار", "صيانة", "نظافة", "أخرى",
        )

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "restaurant.db")
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            DEFAULT_CATEGORIES.forEach { name ->
                                db.execSQL("INSERT INTO categories (name) VALUES (?)", arrayOf(name))
                            }
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
