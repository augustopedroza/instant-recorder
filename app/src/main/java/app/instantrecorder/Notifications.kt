package app.instantrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.instantrecorder.data.Recording
import app.instantrecorder.data.TaskItem
import app.instantrecorder.recording.RecorderState
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.TaskActionReceiver
import app.instantrecorder.tasks.TaskSuggestion
import app.instantrecorder.ui.AlarmActivity
import app.instantrecorder.ui.MainActivity

object Notifications {
    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_RESULTS = "results"
    private const val CHANNEL_REMINDERS = "reminders"
    private const val CHANNEL_ALARMS = "alarms"

    const val ID_SERVICE = 1
    private const val RECORDING_RED = 0xFFD32F2F.toInt()
    private const val ID_PROBLEM = 2
    private fun resultId(recordingId: Long) = 10_000 + recordingId.toInt()
    private fun reminderId(taskId: Long) = 1_000_000 + taskId.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while recording or transcribing"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_RESULTS, "Transcripts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A recording has been transcribed and tasks are ready to review"
                },
                NotificationChannel(CHANNEL_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders for tasks with a due time"
                },
                // Alarm sound + alarm usage: Do Not Disturb lets alarms through by default.
                NotificationChannel(CHANNEL_ALARMS, "Important reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tasks marked Important: ring like an alarm, even in Do Not Disturb"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        )
    }

    fun recording(context: Context, status: RecorderState.Status.Recording): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (status.long) "Long recording…" else "Recording a quick note…")
            .setContentText(
                if (status.long) "Stops at ${formatLimit(status.limitMs)} or when you tap Stop"
                else "Stops after a pause or at ${formatLimit(status.limitMs)}. Tap Long recording to keep going."
            )
            .setWhen(status.startedAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16+ "Live Update": a red status-bar chip with the running timer, visible in every
            // app and on the lock screen, so a forgotten recording is hard to miss.
            .setRequestPromotedOngoing(true)
            .setColor(RECORDING_RED)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stop, "Stop", serviceAction(context, RecordingService.ACTION_STOP))
        if (!status.long) builder.addAction(R.drawable.ic_mic, "Long recording", serviceAction(context, RecordingService.ACTION_LOCK))
        return builder
            .addAction(R.drawable.ic_stop, "Discard", serviceAction(context, RecordingService.ACTION_CANCEL))
            .build()
    }

    private fun formatLimit(ms: Long): String = if (ms % 60_000 == 0L) "${ms / 60_000} min" else "${ms / 1000} s"

    fun working(context: Context, text: String, progress: Int?): Notification =
        NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply { if (progress != null) setProgress(100, progress, progress == 0) }
            .build()

    fun showResult(context: Context, recording: Recording, suggestions: List<TaskSuggestion>) {
        if (DebugKnobs.quietResults) return
        val transcript = recording.transcript.orEmpty()
        val actionable = suggestions.filter { it.actionable }
        val title = when (actionable.size) {
            0 -> "Transcript ready"
            1 -> "1 task found"
            else -> "${actionable.size} tasks found"
        }
        val body = if (actionable.isEmpty()) transcript else actionable.joinToString("\n") { "• " + it.titleWithDue() }
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openRecording(context, recording.id))
        if (actionable.isNotEmpty()) {
            builder.addAction(
                R.drawable.ic_check,
                if (actionable.size == 1) "Save task" else "Save all",
                PendingIntent.getBroadcast(
                    context,
                    recording.id.toInt(),
                    TaskActionReceiver.intent(context, TaskActionReceiver.ACTION_SAVE_ALL)
                        .putExtra(TaskActionReceiver.EXTRA_RECORDING_ID, recording.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        builder.addAction(R.drawable.ic_mic, "Review", openRecording(context, recording.id))
        notify(context, resultId(recording.id), builder.build())
    }

    /** After "Save all": stays until swiped away so its "Delete recording" can still be tapped. */
    fun showSaved(context: Context, recordingId: Long, saved: List<TaskItem>) {
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(if (saved.size == 1) "Task saved" else "${saved.size} tasks saved")
            .setContentText(saved.joinToString(", ") { it.title })
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(
                R.drawable.ic_stop,
                "Delete recording",
                PendingIntent.getBroadcast(
                    context,
                    recordingId.toInt(),
                    TaskActionReceiver.intent(context, TaskActionReceiver.ACTION_DELETE_RECORDING)
                        .putExtra(TaskActionReceiver.EXTRA_RECORDING_ID, recordingId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        notify(context, resultId(recordingId), n)
    }

    fun showReminder(context: Context, task: TaskItem) {
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(task.title)
            .setContentText(task.dueAt?.let { Due(it, task.dueHasTime).label() })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(
                R.drawable.ic_check,
                "Done",
                PendingIntent.getBroadcast(
                    context,
                    task.id.toInt(),
                    TaskActionReceiver.intent(context, TaskActionReceiver.ACTION_MARK_DONE)
                        .putExtra(TaskActionReceiver.EXTRA_TASK_ID, task.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        notify(context, reminderId(task.id), n)
    }

    /** An Important task's reminder: alarm sound once, and the full-screen alarm when the phone is locked. */
    fun showAlarm(context: Context, task: TaskItem) {
        val fullScreen = PendingIntent.getActivity(
            context, task.id.toInt(), AlarmActivity.intent(context, task.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(task.title)
            .setContentText(task.dueAt?.let { Due(it, task.dueHasTime).label() })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_check, "Done", taskAction(context, task.id, TaskActionReceiver.ACTION_MARK_DONE))
            .addAction(R.drawable.ic_mic, "Snooze 10 min", taskAction(context, task.id, TaskActionReceiver.ACTION_SNOOZE))
            .build()
        notify(context, reminderId(task.id), n)
    }

    private fun taskAction(context: Context, taskId: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, (action.hashCode() * 31 + taskId).toInt(),
            TaskActionReceiver.intent(context, action).putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Something needs the user's attention before recording/transcribing can work. */
    fun showProblem(context: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_PROBLEM, n)
    }

    fun cancelResult(context: Context, recordingId: Long) =
        NotificationManagerCompat.from(context).cancel(resultId(recordingId))

    fun cancelReminder(context: Context, taskId: Long) =
        NotificationManagerCompat.from(context).cancel(reminderId(taskId))

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun TaskSuggestion.titleWithDue() = if (due != null) "$title — ${due.label()}" else title

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, MainActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)

    private fun openRecording(context: Context, recordingId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            recordingId.toInt(),
            MainActivity.intent(context).putExtra(MainActivity.EXTRA_RECORDING_ID, recordingId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun serviceAction(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            RecordingService.intent(context, action),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
