package app.instantrecorder.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "3 days before Sarah's birthday", as said: [query] is the event to look up, [phrase] the words used.
 * [everyTime]: "…before every/each board meeting" — a new task for each occurrence of a repeating event.
 */
data class EventRequest(val query: String, val offsetMinutes: Int, val phrase: String, val everyTime: Boolean = false)

/** One calendar occurrence a task can be tied to. All-day events start at UTC midnight (CalendarContract's rule). */
data class EventRef(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val allDay: Boolean,
    val recurring: Boolean = false,
    val calendarColor: Int = 0,
)

/** Pure date math and name matching for event-relative reminders (no Android calls, unit-tested). */
object EventTiming {
    const val HOUR = 60
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    /**
     * The occurrence a "before every <event>" task moves on to once [eventId]'s occurrence at [after] is done:
     * the earliest that *starts* after it (a calendar range query also returns meetings still in progress), of
     * the same event or of any event with the same name. One name can span several series (Ward Council on
     * 2nd and 4th Sundays), and editing "this and following events" splits a series into a new event.
     * On a tie (the same meeting in two calendars) the task's own event wins.
     */
    fun nextOccurrence(candidates: List<EventRef>, eventId: Long, title: String?, after: Long): EventRef? {
        val name = title?.trim()?.takeIf { it.isNotEmpty() }
        return candidates
            .filter { it.begin > after && (it.eventId == eventId || (name != null && it.title.trim().equals(name, ignoreCase = true))) }
            .minWithOrNull(compareBy<EventRef>({ it.begin }, { if (it.eventId == eventId) 0 else 1 }))
    }

    /** Common choices for picking an offset by hand. */
    val PRESETS = listOf(2 * HOUR, DAY, 2 * DAY, 3 * DAY, WEEK, 2 * WEEK)

    /**
     * Whole-day offsets give a date-only due (reminded at 9:00 that morning): "the day before" an evening
     * event means that day, not 24 hours earlier. Hour offsets give an exact time.
     */
    fun due(event: EventRef, offsetMinutes: Int, zone: ZoneId): Due {
        val eventDate = eventDate(event, zone)
        return if (offsetMinutes % DAY == 0) {
            Due(eventDate.minusDays((offsetMinutes / DAY).toLong()).atStartOfDay(zone).toInstant().toEpochMilli(), hasTime = false)
        } else {
            val start = if (event.allDay) eventDate.atStartOfDay(zone).toInstant().toEpochMilli() else event.begin
            Due(start - offsetMinutes * 60_000L, hasTime = true)
        }
    }

    fun eventDate(event: EventRef, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(event.begin).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** "Sun, Nov 8": the event's own date (an all-day event's calendar day, not the UTC instant's local day). */
    fun dateLabel(event: EventRef, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        eventDate(event, zone).format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))

    fun describe(offsetMinutes: Int): String = when {
        offsetMinutes % WEEK == 0 -> plural(offsetMinutes / WEEK, "week")
        offsetMinutes % DAY == 0 -> if (offsetMinutes == DAY) "the day" else plural(offsetMinutes / DAY, "day")
        offsetMinutes % HOUR == 0 -> plural(offsetMinutes / HOUR, "hour")
        else -> plural(offsetMinutes, "minute")
    } + " before"

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"

    /**
     * How well a spoken event name matches a calendar title, 0..1: the share of the spoken words found in the
     * title, ignoring filler ("the", "my") and possessives, and allowing plural/prefix differences.
     */
    fun matchScore(query: String, title: String): Double {
        val wanted = words(query)
        if (wanted.isEmpty()) return 0.0
        val have = words(title)
        val found = wanted.count { w -> have.any { h -> h == w || (w.length >= 4 && h.length >= 4 && (h.startsWith(w) || w.startsWith(h))) } }
        return found.toDouble() / wanted.size
    }

    /** Best upcoming match, earliest first among equally good ones; null if nothing matches well enough. */
    fun bestMatch(query: String, events: List<EventRef>): EventRef? =
        events.map { it to matchScore(query, it.title) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<EventRef, Double>> { it.second }.thenBy { it.first.begin })
            .firstOrNull()?.first

    private const val MIN_SCORE = 0.6
    private val STOP = setOf("the", "my", "our", "a", "an", "of", "for", "to", "with", "his", "her", "their", "at", "on", "in", "next", "this")

    private fun words(text: String): List<String> = text.lowercase()
        .replace('’', '\'')
        .replace(Regex("""'s\b"""), "")
        .split(Regex("""[^\p{L}\p{N}]+"""))
        .filter { it.isNotEmpty() && it !in STOP }
}
