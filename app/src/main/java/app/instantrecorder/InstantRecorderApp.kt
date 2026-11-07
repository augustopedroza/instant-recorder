package app.instantrecorder

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.instantrecorder.data.AppDatabase
import app.instantrecorder.data.AppSettings
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.tasks.EventSync
import app.instantrecorder.transcribe.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide singletons. The accessibility service, recording service and UI all share them. */
class InstantRecorderApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var models: ModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "instant-recorder.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .addCallback(AppDatabase.SEED_ON_CREATE)
            .build()
        settings = AppSettings(this)
        models = ModelManager(this, appScope, settings)
        Notifications.createChannels(this)

        // A transcription can't survive process death, so anything left "in progress" goes back to the queue state.
        appScope.launch {
            db.recordings().resetStatus(from = RecordingStatus.TRANSCRIBING, to = RecordingStatus.RECORDED)
            EventSync.refresh(this@InstantRecorderApp)
        }
        EventSync.scheduleDaily(this)
    }
}

val Context.app: InstantRecorderApp get() = applicationContext as InstantRecorderApp
