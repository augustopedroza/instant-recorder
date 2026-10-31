package app.instantrecorder.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.Category
import app.instantrecorder.data.TaskItem
import app.instantrecorder.recording.RecordingStore
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Saving tasks to the in-app list (and arming their reminders). */
object TaskStore {
    suspend fun save(context: Context, recordingId: Long?, suggestions: List<TaskSuggestion>, notes: String?): List<TaskItem> {
        val app = context.app
        val items = suggestions.map {
            val linked = it.linkedEvent
            TaskItem(
                // Not found in the calendar: keep what was said, so the task still makes sense.
                title = if (it.event != null && linked == null) "${it.title} (${it.event.phrase})" else it.title,
                notes = notes,
                dueAt = it.due?.epochMillis,
                dueHasTime = it.due?.hasTime ?: false,
                recordingId = recordingId,
                categoryId = it.categoryId,
                repeatDays = it.repeatDays,
                eventId = linked?.eventId,
                eventTitle = linked?.title,
                eventBegin = linked?.begin,
                eventOffsetMinutes = it.event?.offsetMinutes?.takeIf { linked != null },
                eventRepeats = it.event?.everyTime == true && linked?.recurring == true,
                important = it.important,
            )
        }
        val ids = app.db.tasks().insertAll(items)
        val saved = items.zip(ids) { task, id -> task.copy(id = id) }
        saved.forEach { Reminders.schedule(context, it) }
        if (recordingId != null) app.db.recordings().addTasksCreated(recordingId, saved.size)
        return saved
    }

    /**
     * Checks [task] off. A repeating task hands its repeat on to a new next occurrence (returned), so only
     * one open copy ever exists.
     */
    suspend fun complete(context: Context, task: TaskItem): TaskItem? {
        setDone(context, task, true)
        val next = when {
            task.eventRepeats -> nextForEvent(context, task) ?: return null
            task.repeatDays != Repeat.NONE -> {
                val due = Repeat.next(task.repeatDays, task.dueAt?.let { Due(it, task.dueHasTime) }, ZonedDateTime.now())
                task.copy(id = 0, done = false, createdAt = System.currentTimeMillis(), dueAt = due.epochMillis, dueHasTime = due.hasTime)
            }
            else -> return null
        }
        val saved = next.copy(id = context.app.db.tasks().insertAll(listOf(next)).single())
        Reminders.schedule(context, saved)
        return saved
    }

    /**
     * The task for the event's next upcoming occurrence (after this one and after now, so finishing late
     * doesn't create tasks for meetings already past). Null if the series ended or the calendar isn't readable.
     */
    private fun nextForEvent(context: Context, task: TaskItem): TaskItem? {
        val eventId = task.eventId ?: return null
        val offset = task.eventOffsetMinutes ?: return null
        val after = maxOf(task.eventBegin ?: 0, System.currentTimeMillis()) + 60_000
        val event = Calendars.nextOccurrence(context, eventId, after) ?: return null
        val due = EventTiming.due(event, offset, java.time.ZoneId.systemDefault())
        return task.copy(
            id = 0, done = false, createdAt = System.currentTimeMillis(), dueAt = due.epochMillis, dueHasTime = due.hasTime,
            eventBegin = event.begin, eventTitle = event.title,
        )
    }

    /** Undoes [complete]: reopens [task] as it was and removes the occurrence it created. */
    suspend fun undoComplete(context: Context, task: TaskItem, next: TaskItem?) {
        next?.let {
            context.app.db.tasks().delete(it)
            Reminders.cancel(context, it.id)
        }
        val reopened = task.copy(done = false, completedAt = null)
        context.app.db.tasks().update(reopened)
        Reminders.schedule(context, reopened)
    }

    /** Puts [task] back exactly as it was, e.g. undoing a move back out of the Done tab. */
    suspend fun restore(context: Context, task: TaskItem) {
        context.app.db.tasks().update(task)
        Reminders.schedule(context, task) // cancels it again if the task is done
        if (task.done) Notifications.cancelReminder(context, task.id)
    }

    suspend fun setDone(context: Context, task: TaskItem, done: Boolean) {
        // A completed occurrence stops repeating; its successor carries the repeat on.
        val updated = if (done) {
            task.copy(done = true, completedAt = System.currentTimeMillis(), repeatDays = Repeat.NONE, eventRepeats = false)
        } else {
            task.copy(done = false, completedAt = null)
        }
        context.app.db.tasks().update(updated)
        if (done) Reminders.cancel(context, task.id) else Reminders.schedule(context, updated)
        Notifications.cancelReminder(context, task.id)
    }

    /**
     * Applies edits to the task as it is in the database now (a reminder's "Done" may have changed it
     * while the editor was open), then re-arms or cancels its reminder for the new due date.
     */
    suspend fun edit(
        context: Context, taskId: Long, title: String, notes: String?, due: Due?, categoryId: Long?, repeatDays: Int,
        link: EventLink?, important: Boolean,
    ) {
        val dao = context.app.db.tasks()
        val current = dao.get(taskId) ?: return
        val updated = current.copy(
            title = title, notes = notes, dueAt = due?.epochMillis, dueHasTime = due?.hasTime ?: false, categoryId = categoryId,
            repeatDays = repeatDays, eventId = link?.event?.eventId, eventTitle = link?.event?.title,
            eventBegin = link?.event?.begin, eventOffsetMinutes = link?.offsetMinutes, eventRepeats = link?.everyTime == true,
            important = important,
        )
        dao.update(updated)
        Reminders.schedule(context, updated)
        Notifications.cancelReminder(context, taskId)
    }

    suspend fun delete(context: Context, task: TaskItem) {
        context.app.db.tasks().delete(task)
        Reminders.cancel(context, task.id)
    }
}

fun Category.rule() = CategoryRule(id, name, keywordList)

/**
 * A task's tie to a calendar event: which occurrence, how long before it to be reminded, and whether a new
 * task follows for every occurrence ([everyTime], only meaningful for repeating events).
 */
data class EventLink(val event: EventRef, val offsetMinutes: Int, val everyTime: Boolean = false) {
    fun describe() = "${EventTiming.describe(offsetMinutes)} ${if (everyTime) "every " else ""}${event.title}"
}

/** The link stored on [this] task, if any. */
val TaskItem.eventLink: EventLink?
    get() = if (eventId != null && eventBegin != null && eventOffsetMinutes != null) {
        // Only repeating events can repeat, so a stored "every time" also tells us the event repeats.
        EventLink(EventRef(eventId, eventTitle.orEmpty(), eventBegin, allDay = false, recurring = eventRepeats), eventOffsetMinutes, eventRepeats)
    } else {
        null
    }

/** Fills in each suggestion's calendar event (when allowed and found) and the due date that follows from it. */
fun List<TaskSuggestion>.withCalendarEvents(context: Context): List<TaskSuggestion> {
    if (none { it.event != null } || !Calendars.hasPermission(context)) return this
    val zone = java.time.ZoneId.systemDefault()
    return map { s ->
        val request = s.event ?: return@map s
        val event = Calendars.find(context, request.query) ?: return@map s
        s.copy(linkedEvent = event, due = EventTiming.due(event, request.offsetMinutes, zone))
    }
}

/** Hand-offs to other apps. Neither needs a permission. */
object TaskIntents {
    fun calendar(title: String, notes: String?, due: Due?): Intent {
        val start = due?.epochMillis ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
        val allDay = due != null && !due.hasTime
        return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + TimeUnit.MINUTES.toMillis(30))
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
    }

    /** One task per line, which Todoist/TickTick/Keep all turn into sensible items. */
    fun shareText(tasks: List<Pair<String, Due?>>): String =
        tasks.joinToString("\n") { (title, due) -> if (due != null) "$title (${due.label()})" else title }

    fun share(tasks: List<Pair<String, Due?>>): Intent {
        val text = shareText(tasks)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, tasks.singleOrNull()?.first ?: "Tasks")
        return Intent.createChooser(send, "Create task with…")
    }
}

object Reminders {
    /** Date-only tasks remind at 9:00 on the day. */
    private fun triggerAt(task: TaskItem): Long? =
        task.dueAt?.let { if (task.dueHasTime) it else it + TimeUnit.HOURS.toMillis(9) }

    fun schedule(context: Context, task: TaskItem) {
        val at = triggerAt(task)
        if (at == null || task.done || at <= System.currentTimeMillis()) {
            cancel(context, task.id)
            return
        }
        setAlarm(context, task, at)
    }

    /** Ring again in [minutes], whatever the due date says. */
    fun snooze(context: Context, task: TaskItem, minutes: Long = 10) =
        setAlarm(context, task, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes))

    private fun setAlarm(context: Context, task: TaskItem, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, task.id)
        when {
            // Important: an alarm clock alarm — exact even in deep sleep, shown as the next alarm.
            task.important && alarms.canScheduleExactAlarms() ->
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, openTasks(context)), pending)
            // USE_EXACT_ALARM (a reminder app) normally allows this; otherwise Android may be a few minutes late.
            alarms.canScheduleExactAlarms() -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, taskId))
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            TaskActionReceiver.intent(context, TaskActionReceiver.ACTION_REMIND).putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openTasks(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, app.instantrecorder.ui.MainActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)
}

/** Notification buttons ("Save all", "Done") and reminder alarms. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                handle(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val db = context.app.db
        when (intent.action) {
            ACTION_SAVE_ALL -> {
                val recordingId = intent.getLongExtra(EXTRA_RECORDING_ID, -1)
                val recording = db.recordings().get(recordingId) ?: return
                val transcript = recording.transcript ?: return
                val suggestions = TaskExtractor.extract(transcript, categories = db.categories().all().map { it.rule() })
                    .withCalendarEvents(context)
                val chosen = suggestions.filter { it.actionable }.ifEmpty { suggestions }
                val saved = TaskStore.save(context, recordingId, chosen, transcript)
                Notifications.showSaved(context, recordingId, saved)
            }
            ACTION_DELETE_RECORDING -> RecordingStore.delete(context, intent.getLongExtra(EXTRA_RECORDING_ID, -1))
            ACTION_MARK_DONE -> {
                val task = db.tasks().get(intent.getLongExtra(EXTRA_TASK_ID, -1)) ?: return
                TaskStore.complete(context, task)
            }
            ACTION_SYNC_EVENTS -> EventSync.refresh(context)
            ACTION_REMIND -> {
                val task = db.tasks().get(intent.getLongExtra(EXTRA_TASK_ID, -1)) ?: return
                if (task.done) return
                if (task.important) Notifications.showAlarm(context, task) else Notifications.showReminder(context, task)
            }
            ACTION_SNOOZE -> {
                val task = db.tasks().get(intent.getLongExtra(EXTRA_TASK_ID, -1)) ?: return
                Notifications.cancelReminder(context, task.id)
                Reminders.snooze(context, task)
            }
        }
    }

    companion object {
        private const val TAG = "TaskActionReceiver"
        const val ACTION_SAVE_ALL = "app.instantrecorder.action.SAVE_ALL"
        const val ACTION_DELETE_RECORDING = "app.instantrecorder.action.DELETE_RECORDING"
        const val ACTION_MARK_DONE = "app.instantrecorder.action.MARK_DONE"
        const val ACTION_REMIND = "app.instantrecorder.action.REMIND"
        const val ACTION_SYNC_EVENTS = "app.instantrecorder.action.SYNC_EVENTS"
        const val ACTION_SNOOZE = "app.instantrecorder.action.SNOOZE"
        const val EXTRA_RECORDING_ID = "recordingId"
        const val EXTRA_TASK_ID = "taskId"

        fun intent(context: Context, action: String) = Intent(context, TaskActionReceiver::class.java).setAction(action)
    }
}

/** Alarms don't survive a reboot, so re-arm every pending reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                context.app.db.tasks().openWithDueDate().forEach { Reminders.schedule(context, it) }
                EventSync.scheduleDaily(context)
                EventSync.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
