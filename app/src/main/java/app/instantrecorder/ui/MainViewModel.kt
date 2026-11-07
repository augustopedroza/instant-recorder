package app.instantrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.instantrecorder.app
import app.instantrecorder.data.Category
import app.instantrecorder.data.Recording
import app.instantrecorder.data.Settings
import app.instantrecorder.data.TaskItem
import app.instantrecorder.recording.RecorderState
import app.instantrecorder.recording.RecordingStore
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.EventLink
import app.instantrecorder.tasks.EventRequest
import app.instantrecorder.tasks.Reminders
import app.instantrecorder.tasks.Repeat
import app.instantrecorder.tasks.TaskStore
import app.instantrecorder.tasks.TaskSuggestion
import app.instantrecorder.tasks.eventLink
import app.instantrecorder.transcribe.WhisperLib
import app.instantrecorder.transcribe.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something the user can still take back while its snackbar is showing. */
sealed interface Undoable {
    val message: String
}

/** A task checked off and moved to the Done tab; [next] is the occurrence that took over a repeating one. */
data class Completion(val task: TaskItem, val next: TaskItem?) : Undoable {
    override val message get() = next?.dueAt?.let { "Done · next: ${Due(it, next.dueHasTime).label()}" } ?: "Moved to Done"

    /** Repeating tasks get a pop-up saying when the next one is, instead of a snackbar. */
    val repeating get() = task.repeatDays != Repeat.NONE || task.eventRepeats

    /** "Next reminder: Sun, Nov 15, 1 week before Ward Council on Sun, Nov 22." */
    val nextReminder: String
        get() {
            val n = next ?: return "There's no upcoming ${task.eventTitle ?: "event"} in your calendar, so there's no next reminder."
            val due = n.dueAt?.let { Due(it, n.dueHasTime).label() } ?: return "The next one is in your Tasks list."
            return "Next reminder: $due${n.eventLink?.let { ", ${it.describe()}" }.orEmpty()}."
        }
}

/** A finished task unchecked in the Done tab, back in the Tasks list. [task] is how it was while done. */
data class Reopening(val task: TaskItem) : Undoable {
    override val message get() = "Back in Tasks"
}

/** Something the user deleted, still undoable until its snackbar goes away. */
sealed interface Deletion : Undoable {
    data class OfRecording(val recording: Recording) : Deletion {
        override val message get() = "Recording deleted"
    }

    data class OfTask(val task: TaskItem) : Deletion {
        override val message get() = "Task deleted"
    }

    /** "Delete all" in the Done tab. */
    data class OfTasks(val tasks: List<TaskItem>) : Deletion {
        override val message get() = if (tasks.size == 1) "Task deleted" else "${tasks.size} tasks deleted"
    }
}

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data class Review(val recordingId: Long) : Route
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.app
    private val context get() = getApplication<Application>()

    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route.asStateFlow()

    /**
     * Deleted items are hidden at once and only really deleted when their "Undo" snackbar goes away.
     * Ids stay hidden after the delete so a row can't flash back before Room re-emits (Room never reuses them).
     */
    private val hiddenRecordings = MutableStateFlow<Set<Long>>(emptySet())
    private val hiddenTasks = MutableStateFlow<Set<Long>>(emptySet())

    val recordings: StateFlow<List<Recording>> =
        combine(graph.db.recordings().observeAll(), hiddenRecordings) { all, hidden -> all.filterNot { it.id in hidden } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks: StateFlow<List<TaskItem>> =
        combine(graph.db.tasks().observeAll(), hiddenTasks) { all, hidden -> all.filterNot { it.id in hidden } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Actions waiting for the home screen's Undo snackbar; buffered so none is missed while navigating there. */
    private val pendingUndoables = Channel<Undoable>(Channel.BUFFERED)
    val undoables: Flow<Undoable> = pendingUndoables.receiveAsFlow()

    val settings: StateFlow<Settings> = graph.settings.state
    val installedModels = graph.models.installed
    val downloads = graph.models.downloads
    val modelErrors = graph.models.errors

    val recorderStatus = RecorderState.status
    val level = RecorderState.level
    val transcribing = RecorderState.transcribing

    val whisperSystemInfo: String by lazy { runCatching { WhisperLib.nativeSystemInfo() }.getOrDefault("unavailable") }

    fun open(route: Route) {
        _route.value = route
    }

    fun recording(id: Long): Flow<Recording?> = graph.db.recordings().observe(id)

    fun toggleRecording() = RecordingService.send(context, RecordingService.ACTION_TOGGLE)
    fun startLongRecording() = RecordingService.send(context, RecordingService.ACTION_START_LONG)
    fun lockRecording() = RecordingService.send(context, RecordingService.ACTION_LOCK)
    fun stopRecording() = RecordingService.send(context, RecordingService.ACTION_STOP)
    fun discardRecording() = RecordingService.send(context, RecordingService.ACTION_CANCEL)

    fun transcribe(recordingId: Long) = RecordingService.send(context, RecordingService.ACTION_TRANSCRIBE) {
        putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
    }

    /** Every delete in the app goes through here, so all of them can be undone the same way. */
    fun delete(item: Deletion) {
        when (item) {
            is Deletion.OfRecording -> hiddenRecordings.update { it + item.recording.id }
            is Deletion.OfTask -> {
                hiddenTasks.update { it + item.task.id }
                Reminders.cancel(context, item.task.id) // don't remind about a task that's on its way out
            }
            is Deletion.OfTasks -> hiddenTasks.update { it + item.tasks.map(TaskItem::id) } // done: no reminders
        }
        pendingUndoables.trySend(item)
    }

    /** The snackbar's Undo was tapped. */
    fun undo(item: Undoable) {
        when (item) {
            is Deletion -> undoDeletion(item)
            is Completion -> graph.appScope.launch { TaskStore.undoComplete(context, item.task, item.next) }
            is Reopening -> graph.appScope.launch { TaskStore.restore(context, item.task) }
        }
    }

    /** The snackbar went away without Undo. Completions and reopenings are already applied. */
    fun commit(item: Undoable) {
        if (item is Deletion) commitDeletion(item)
    }

    private fun undoDeletion(item: Deletion) {
        when (item) {
            is Deletion.OfRecording -> hiddenRecordings.update { it - item.recording.id }
            is Deletion.OfTask -> {
                hiddenTasks.update { it - item.task.id }
                Reminders.schedule(context, item.task)
            }
            is Deletion.OfTasks -> hiddenTasks.update { it - item.tasks.map(TaskItem::id).toSet() }
        }
    }

    private fun commitDeletion(item: Deletion) {
        // App scope: when the activity finishes with a snackbar up, this runs after viewModelScope is cancelled.
        graph.appScope.launch(Dispatchers.IO) {
            when (item) {
                is Deletion.OfRecording -> {
                    // A queued recording can get picked up for transcription while its snackbar is showing.
                    if (!RecordingStore.delete(context, item.recording.id)) hiddenRecordings.update { it - item.recording.id }
                }
                is Deletion.OfTask -> TaskStore.delete(context, item.task)
                is Deletion.OfTasks -> item.tasks.forEach { TaskStore.delete(context, it) }
            }
        }
    }

    fun saveTranscript(recordingId: Long, text: String) {
        viewModelScope.launch { graph.db.recordings().setTranscript(recordingId, text.trim()) }
    }

    fun saveTasks(recordingId: Long, suggestions: List<TaskSuggestion>, notes: String?, onSaved: (Int) -> Unit) {
        viewModelScope.launch {
            val saved = TaskStore.save(context, recordingId, suggestions, notes)
            onSaved(saved.size)
        }
    }

    /** Either way the task changes tabs, so the snackbar says where it went and offers Undo. */
    fun setTaskDone(task: TaskItem, done: Boolean) {
        viewModelScope.launch {
            if (done) {
                pendingUndoables.send(Completion(task, TaskStore.complete(context, task)))
            } else {
                TaskStore.setDone(context, task, false)
                pendingUndoables.send(Reopening(task))
            }
        }
    }

    /** A task typed in by hand, not from a recording. */
    fun addTask(title: String, notes: String?, due: Due?, categoryId: Long?, repeatDays: Int, link: EventLink?, important: Boolean) {
        viewModelScope.launch {
            val task = TaskSuggestion(
                title, due, actionable = true, categoryId = categoryId, repeatDays = repeatDays,
                event = link?.let { EventRequest(it.event.title, it.offsetMinutes, "", it.everyTime) }, linkedEvent = link?.event,
                important = important,
            )
            TaskStore.save(context, recordingId = null, listOf(task), notes)
        }
    }

    fun editTask(
        taskId: Long, title: String, notes: String?, due: Due?, categoryId: Long?, repeatDays: Int, link: EventLink?, important: Boolean,
    ) {
        viewModelScope.launch { TaskStore.edit(context, taskId, title, notes, due, categoryId, repeatDays, link, important) }
    }

    val categories: StateFlow<List<Category>> = graph.db.categories().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveCategory(category: Category) {
        viewModelScope.launch {
            val dao = graph.db.categories()
            if (category.id == 0L) dao.insert(category.copy(position = dao.nextPosition())) else dao.update(category)
        }
    }

    /** Its tasks stay, uncategorized. */
    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            graph.db.tasks().clearCategory(category.id)
            graph.db.categories().delete(category)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) = graph.settings.update(transform)
    fun downloadModel(model: WhisperModel) = graph.models.download(model)
    fun deleteModel(model: WhisperModel) = graph.models.delete(model)
    fun selectModel(model: WhisperModel) = updateSettings { it.copy(modelId = model.id) }
}
