package com.example.tallycustomerapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CompanyEntity::class,
        LedgerEntity::class,
        VoucherEntity::class,
        VoucherEntryEntity::class,
        StockItemEntity::class,
        SyncStateEntity::class,
        PageSnapshotEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineDao
    abstract fun companyDao(): CompanyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS page_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        companyId INTEGER NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        htmlGzip BLOB NOT NULL,
                        lastCaptured INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_page_snapshots_companyId ON page_snapshots(companyId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_page_snapshots_companyId_url ON page_snapshots(companyId, url)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_page_snapshots_companyId_lastCaptured ON page_snapshots(companyId, lastCaptured)")
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tally_offline_final.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
