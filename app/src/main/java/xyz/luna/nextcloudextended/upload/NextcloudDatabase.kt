package xyz.luna.nextcloudextended.upload

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UploadEntity::class, OfflineFileEntity::class, DownloadEntity::class, OfflineOperationEntity::class], version = 5, exportSchema = true)
abstract class NextcloudDatabase : RoomDatabase() {
    abstract fun uploads(): UploadDao
    abstract fun offlineFiles(): OfflineFileDao
    abstract fun downloads(): DownloadDao
    abstract fun offlineOperations(): OfflineOperationDao

    companion object {
        @Volatile private var instance: NextcloudDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_operations ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS offline_files (accountId TEXT NOT NULL, remotePath TEXT NOT NULL, localPath TEXT NOT NULL, size INTEGER NOT NULL, pinned INTEGER NOT NULL, lastAccess INTEGER NOT NULL, PRIMARY KEY(accountId, remotePath))")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS download_operations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, accountId TEXT NOT NULL, remotePath TEXT NOT NULL, fileName TEXT NOT NULL, destination TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT, createdAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS offline_operations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, accountId TEXT NOT NULL, operationType TEXT NOT NULL, path TEXT NOT NULL, extra TEXT, createdAt INTEGER NOT NULL)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun get(context: Context): NextcloudDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NextcloudDatabase::class.java,
                "nextcloud-extended.db"
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
