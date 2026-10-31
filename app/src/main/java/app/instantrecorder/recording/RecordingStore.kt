package app.instantrecorder.recording

import android.content.Context
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.RecordingStatus
import java.io.File

object RecordingStore {
    /**
     * Deletes the recording's audio and its row. Its tasks stay; they carry the transcript in their notes.
     * Returns false, and keeps it, if it's being transcribed right now.
     */
    suspend fun delete(context: Context, recordingId: Long): Boolean {
        val dao = context.app.db.recordings()
        val current = dao.get(recordingId) ?: return true
        if (current.status == RecordingStatus.TRANSCRIBING) return false
        File(current.filePath).delete()
        dao.delete(current)
        Notifications.cancelResult(context, recordingId)
        return true
    }
}
