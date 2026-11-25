package com.scanpesa.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val number: String,
    val account: String?,
    val target: String,
    val createdAt: Long,
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY createdAt DESC LIMIT 50")
    fun recent(): Flow<List<ScanEntity>>

    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Query("DELETE FROM scans WHERE id NOT IN (SELECT id FROM scans ORDER BY createdAt DESC LIMIT 50)")
    suspend fun trim()

    @Transaction
    suspend fun insertAndTrim(scan: ScanEntity) {
        insert(scan)
        trim()
    }

    @Query("DELETE FROM scans")
    suspend fun clear()
}

@Database(entities = [ScanEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scans(): ScanDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "scanpesa.db")
                .build().also { instance = it }
        }
    }
}
