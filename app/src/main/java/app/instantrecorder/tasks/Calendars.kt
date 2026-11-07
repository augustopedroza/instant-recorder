package app.instantrecorder.tasks

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import app.instantrecorder.Notifications
import app.instantrecorder.app
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Reads the phone's calendars (never writes). Only calendars shown in the calendar app ("visible") are
 * searched, minus any the user switched off in Settings. Nothing leaves the phone.
 */
object Calendars {
    data class Calendar(val id: Long, val name: String, val account: String, val color: Int)

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun visible(context: Context): List<Calendar> {
        if (!hasPermission(context)) return emptyList()
        val c = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
        )
        return context.contentResolver.query(c, projection, "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(Calendar(cur.getLong(0), cur.getString(1).orEmpty(), cur.getString(2).orEmpty(), cur.getInt(3))) }
        }.orEmpty().sortedBy { it.name.lowercase() }
    }

    /** Upcoming occurrences in the searched calendars, earliest first. */
    fun upcoming(context: Context, days: Long = 400, from: Long = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12)): List<EventRef> {
        if (!hasPermission(context)) return emptyList()
        val excluded = context.app.settings.value.excludedCalendarIds
        return instances(context, from, from + TimeUnit.DAYS.toMillis(days), null)
            .filter { (_, calendarId) -> calendarId !in excluded }
            .map { it.first }
    }

    fun find(context: Context, query: String): EventRef? =
        EventTiming.bestMatch(query, upcoming(context).filter { it.begin > System.currentTimeMillis() })

    /** The first occurrence of [eventId] still running at [from] or starting after it, or null if the event is gone. */
    fun nextOccurrence(context: Context, eventId: Long, from: Long): EventRef? =
        instances(context, from, from + TimeUnit.DAYS.toMillis(800), eventId).firstOrNull()?.first

    /**
     * Where a "before every <event>" task goes next after the occurrence at [after]: see [EventTiming.nextOccurrence].
     * Same-name events only count from calendars that are searched.
     */
    fun followingOccurrence(context: Context, eventId: Long, title: String?, after: Long): EventRef? {
        val excluded = context.app.settings.value.excludedCalendarIds
        val candidates = instances(context, after, after + TimeUnit.DAYS.toMillis(800), eventId, title?.trim()?.takeIf { it.isNotEmpty() })
            .filter { (ref, calendarId) -> ref.eventId == eventId || calendarId !in excluded }
            .map { it.first }
        return EventTiming.nextOccurrence(candidates, eventId, title, after)
    }

    /** Occurrences overlapping [from]..[to]: of [eventId] if given, or also any titled [title] (ignoring case). */
    private fun instances(context: Context, from: Long, to: Long, eventId: Long?, title: String? = null): List<Pair<EventRef, Long>> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.RRULE, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_COLOR,
        )
        val selection = buildString {
            append("${CalendarContract.Instances.VISIBLE} = 1")
            when {
                eventId != null && title != null ->
                    append(" AND (${CalendarContract.Instances.EVENT_ID} = $eventId OR ${CalendarContract.Instances.TITLE} = ? COLLATE NOCASE)")
                eventId != null -> append(" AND ${CalendarContract.Instances.EVENT_ID} = $eventId")
            }
        }
        val args = if (eventId != null && title != null) arrayOf(title) else null
        return try {
            context.contentResolver.query(uri, projection, selection, args, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                buildList {
                    while (cur.moveToNext()) {
                        val ref = EventRef(
                            eventId = cur.getLong(0), title = cur.getString(1).orEmpty(), begin = cur.getLong(2),
                            allDay = cur.getInt(3) == 1, recurring = !cur.getString(4).isNullOrEmpty(), calendarColor = cur.getInt(6),
                        )
                        add(ref to cur.getLong(5))
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w("Calendars", "Calendar permission was revoked", e)
            emptyList()
        }
    }
}

/**
 * Keeps event-linked reminders on the event: if a (non-repeating) event is moved, the task's due date moves
 * with it, and a "before every <event>" task whose meeting has started moves on to the next one. Runs when
 * the app starts, once a day, after a reboot, and when such a meeting starts.
 */
object EventSync {
    suspend fun refresh(context: Context) {
        if (!Calendars.hasPermission(context)) return
        val dao = context.app.db.tasks()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        for (task in dao.openLinkedToEvents()) {
            val begin = task.eventBegin ?: continue
            if (task.eventOffsetMinutes == null) continue
            if (task.missedItsMeeting(now)) {
                val next = Calendars.followingOccurrence(context, task.eventId ?: continue, task.eventTitle, now) ?: continue
                val moved = task.movedTo(next, zone)
                dao.update(moved)
                Notifications.cancelReminder(context, task.id) // its Done button would now finish the next meeting's task
                Reminders.schedule(context, moved)
                Log.i("EventSync", "Task ${task.id} moved on to the meeting at ${next.begin}")
                continue
            }
            Reminders.scheduleMoveOn(context, task) // tasks saved before 0.11 have no move-on alarm yet
            if (begin < now) continue // that occurrence has passed: leave the task as it is
            val current = Calendars.nextOccurrence(context, task.eventId ?: continue, now - TimeUnit.DAYS.toMillis(1)) ?: continue
            // A moved single occurrence of a series becomes a separate event in the calendar; don't guess.
            if (current.recurring || (current.begin == begin && current.title == task.eventTitle)) continue
            val updated = task.movedTo(current, zone)
            dao.update(updated)
            Reminders.schedule(context, updated)
            Log.i("EventSync", "Task ${task.id} follows its event to ${current.begin}")
        }
    }

    fun scheduleDaily(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, SYNC_REQUEST_CODE,
            TaskActionReceiver.intent(context, TaskActionReceiver.ACTION_SYNC_EVENTS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setInexactRepeating(
            AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR * 6, AlarmManager.INTERVAL_DAY, pending,
        )
    }

    private const val SYNC_REQUEST_CODE = 0x5EC
}
