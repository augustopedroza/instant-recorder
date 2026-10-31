package app.instantrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class RecordingStatus { RECORDED, TRANSCRIBING, DONE, NO_SPEECH, NEEDS_MODEL, FAILED }

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val createdAt: Long,
    val durationMs: Long,
    val status: RecordingStatus = RecordingStatus.RECORDED,
    val transcript: String? = null,
    val error: String? = null,
    /** Wall-clock time the last transcription took, to judge model speed on this phone. */
    val transcribeMs: Long? = null,
    val modelId: String? = null,
    val tasksCreated: Int = 0,
)

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String? = null,
    /** Epoch millis. When [dueHasTime] is false this is local midnight of the due day. */
    val dueAt: Long? = null,
    val dueHasTime: Boolean = false,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val recordingId: Long? = null,
    val categoryId: Long? = null,
    /** Weekly repeat days (see tasks.Repeat); 0 = one-off. Only the open occurrence carries it. */
    @ColumnInfo(defaultValue = "0") val repeatDays: Int = 0,
    /** Reminder tied to a calendar event: the event, the occurrence's start, and how long before it. */
    val eventId: Long? = null,
    val eventTitle: String? = null,
    val eventBegin: Long? = null,
    val eventOffsetMinutes: Int? = null,
    /** Repeat for every occurrence of the (repeating) event: checking it off creates the one for the next. */
    @ColumnInfo(defaultValue = "0") val eventRepeats: Boolean = false,
    /** Remind like an alarm: alarm sound, through Do Not Disturb, full screen when locked. */
    @ColumnInfo(defaultValue = "0") val important: Boolean = false,
    /** When it was checked off; null while open. Orders the Done tab. */
    val completedAt: Long? = null,
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB. */
    val color: Int,
    /** Comma-separated words or phrases that suggest this category, e.g. "office, meeting, report". */
    val keywords: String = "",
    val position: Int = 0,
) {
    val keywordList: List<String> get() = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus)

    @Query("UPDATE recordings SET transcript = :transcript WHERE id = :id")
    suspend fun setTranscript(id: Long, transcript: String)

    @Query("UPDATE recordings SET tasksCreated = tasksCreated + :count WHERE id = :id")
    suspend fun addTasksCreated(id: Long, count: Int)

    @Query("UPDATE recordings SET status = :to WHERE status = :from")
    suspend fun resetStatus(from: RecordingStatus, to: RecordingStatus)

    @Query("SELECT id FROM recordings WHERE status = :status ORDER BY createdAt")
    suspend fun idsWithStatus(status: RecordingStatus): List<Long>

    @Query("SELECT id FROM recordings")
    suspend fun allIds(): List<Long>
}

@Dao
interface TaskDao {
    @Query(
        "SELECT * FROM tasks ORDER BY done ASC, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC"
    )
    fun observeAll(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskItem?

    @Insert
    suspend fun insertAll(tasks: List<TaskItem>): List<Long>

    @Update
    suspend fun update(task: TaskItem)

    @Delete
    suspend fun delete(task: TaskItem)

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueAt IS NOT NULL")
    suspend fun openWithDueDate(): List<TaskItem>

    @Query("SELECT * FROM tasks WHERE done = 0 AND eventId IS NOT NULL")
    suspend fun openLinkedToEvents(): List<TaskItem>

    @Query("UPDATE tasks SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY position, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY position, id")
    suspend fun all(): List<Category>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM categories")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)
}

@Database(entities = [Recording::class, TaskItem::class, Category::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun tasks(): TaskDao
    abstract fun categories(): CategoryDao

    companion object {
        /** The categories every install starts with; users can rename, recolor or delete them. */
        private const val SEED_CATEGORIES =
            "INSERT INTO `categories` (`name`, `color`, `keywords`, `position`) VALUES " +
                "('Personal', ${0xFF43A047.toInt()}, '', 0), ('Work', ${0xFF1E88E5.toInt()}, '', 1)"

        /** v2 added categories. Existing tasks are kept and start uncategorized. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `categoryId` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `color` INTEGER NOT NULL, `keywords` TEXT NOT NULL, `position` INTEGER NOT NULL)"
                )
                db.execSQL(SEED_CATEGORIES)
            }
        }

        /** v3 added repeating tasks. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `repeatDays` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 added reminders tied to calendar events. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `eventId` INTEGER")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `eventTitle` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `eventBegin` INTEGER")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `eventOffsetMinutes` INTEGER")
            }
        }

        /** v5: event reminders that repeat for every occurrence. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `eventRepeats` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6: important reminders that ring like an alarm. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `important` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7: completion time for the Done tab. Tasks finished before it count as finished when created. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `completedAt` INTEGER")
                db.execSQL("UPDATE `tasks` SET `completedAt` = `createdAt` WHERE `done` = 1")
            }
        }

        /** Fresh installs get the same starting categories as upgraded ones. */
        val SEED_ON_CREATE = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(SEED_CATEGORIES)
            }
        }
    }
}
