package xyz.luna.nextcloudextended.upload

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NextcloudDatabase::class.java
    )

    @Test
    fun createDatabaseAndInsertUpload_works() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            NextcloudDatabase::class.java
        ).build()
        db.uploads().insert(
            UploadEntity(accountId = "a", source = "/tmp/a.txt", parent = "/remote.php/dav/files/u/", name = "a.txt", length = 3)
        )
        val row = db.uploads().nextPending()
        check(row != null) { "Expected inserted upload to be pending" }
        check(row.name == "a.txt")
        db.close()
    }

    @Test
    fun migrate1To5PreservesUploads() {
        helper.createDatabase("test-db", 1).apply {
            execSQL("INSERT INTO upload_operations (id, source, parent, name, length, state, attempts, lastError, createdAt) " +
                "VALUES (NULL, '/tmp/a.txt', '/remote.php/dav/files/u/', 'a.txt', 3, 'QUEUED', 0, NULL, 1000)")
        }.close()

        val database = helper.runMigrationsAndValidate("test-db", 5, true, *NextcloudDatabase.MIGRATIONS)

        database.query("SELECT source, name FROM upload_operations").use { cursor ->
            check(cursor.moveToFirst()) { "Expected the migrated upload row" }
            check(cursor.getString(0) == "/tmp/a.txt") { "Unexpected source" }
            check(cursor.getString(1) == "a.txt") { "Unexpected name" }
        }
        database.close()
    }

    @Test
    fun migrate2To5AddsOfflineTables() {
        helper.createDatabase("test-db2", 2).apply {
            execSQL("ALTER TABLE upload_operations ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
        }.close()

        val database = helper.runMigrationsAndValidate("test-db2", 5, true, *NextcloudDatabase.MIGRATIONS)

        database.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            val names = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            check("offline_files" in names) { "offline_files missing" }
            check("download_operations" in names) { "download_operations missing" }
            check("offline_operations" in names) { "offline_operations missing" }
        }
        database.close()
    }
}