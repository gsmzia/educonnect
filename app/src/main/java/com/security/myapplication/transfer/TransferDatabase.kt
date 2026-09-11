package com.security.myapplication.transfer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Singleton Room database holding all transfer records.
 *
 * Existing released schemas are migrated explicitly. Transfer state is user data:
 * an unknown future schema must fail loudly rather than silently deleting queued
 * messages/files through a destructive fallback.
 */
@Database(entities = [TransferRecord::class], version = 3, exportSchema = false)
abstract class TransferDatabase : RoomDatabase() {

    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: TransferDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_status` ON `transfers` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_chatType_peerId_senderId` ON `transfers` (`chatType`, `peerId`, `senderId`)")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transfers` ADD COLUMN `durationSec` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): TransferDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TransferDatabase::class.java,
                    "smart_classroom_transfers.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
