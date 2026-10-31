package app.instantrecorder.ui

import android.Manifest
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.instantrecorder.Notifications
import app.instantrecorder.data.Category
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.tasks.Calendars
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.EventLink
import app.instantrecorder.tasks.EventRequest
import app.instantrecorder.tasks.EventTiming
import app.instantrecorder.tasks.Repeat
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.tasks.TaskIntents
import app.instantrecorder.tasks.TaskSuggestion
import app.instantrecorder.tasks.rule
import app.instantrecorder.transcribe.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A suggested task while it's being edited on the review screen. */
private class EditableTask(
    title: String, due: Due?, selected: Boolean, categoryId: Long?, repeatDays: Int = Repeat.NONE,
    /** What was said ("3 days before the board meeting"), resolved against the calendar when allowed. */
    val event: EventRequest? = null,
) {
    var title by mutableStateOf(title)
    var due by mutableStateOf(due)
    var selected by mutableStateOf(selected)
    var categoryId by mutableStateOf(categoryId)
    var repeatDays by mutableIntStateOf(repeatDays)
    var link by mutableStateOf<EventLink?>(null)
    var eventLookedUp by mutableStateOf(false)
    var saved by mutableStateOf(false)

    fun toSuggestion() = TaskSuggestion(
        title.trim(), due, actionable = true, categoryId = categoryId, repeatDays = repeatDays,
        event = link?.let { EventRequest(it.event.title, it.offsetMinutes, event?.phrase.orEmpty(), it.everyTime) } ?: event,
        linkedEvent = link?.event,
    )
}

private fun editableFrom(suggestions: List<TaskSuggestion>) =
    suggestions.map { EditableTask(it.title, it.due, it.actionable || suggestions.size == 1, it.categoryId, it.repeatDays, it.event) }
        .toMutableStateList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: MainViewModel, recordingId: Long) {
    val context = LocalContext.current
    val recording by remember(recordingId) { vm.recording(recordingId) }.collectAsStateWithLifecycle(null)
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    // How many tasks the last Save created, while the "Delete the recording?" offer is showing.
    var offerDelete by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(recordingId) { Notifications.cancelResult(context, recordingId) }

    val rec = recording
    val transcript = rec?.transcript
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rules = remember(categories) { categories.map { it.rule() } }
    val tasks = remember(recordingId, transcript, rules) { editableFrom(TaskExtractor.extract(transcript.orEmpty(), categories = rules)) }
    // "…before the board meeting": find the event in the calendar (once allowed) and date the task from it.
    var calendarAllowed by remember { mutableStateOf(Calendars.hasPermission(context)) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAllowed = it }
    LaunchedEffect(tasks, calendarAllowed) {
        if (!calendarAllowed) return@LaunchedEffect
        val zone = java.time.ZoneId.systemDefault()
        for (t in tasks) {
            val request = t.event ?: continue
            if (t.link != null || t.eventLookedUp) continue
            val found = withContext(Dispatchers.IO) { Calendars.find(context, request.query) }
            t.eventLookedUp = true
            if (found != null) {
                t.link = EventLink(found, request.offsetMinutes, everyTime = request.everyTime && found.recurring)
                t.due = EventTiming.due(found, request.offsetMinutes, zone)
            }
        }
    }
    val selected = tasks.filter { it.selected && !it.saved && it.title.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.open(Route.Home) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text(rec?.let { formatTimestamp(it.createdAt) } ?: "") },
                actions = {
                    if (rec != null) PlayButton(rec.filePath)
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Transcribe again") },
                            enabled = rec != null && rec.status != RecordingStatus.TRANSCRIBING,
                            onClick = { menuOpen = false; vm.transcribe(recordingId) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete recording") },
                            onClick = {
                                menuOpen = false
                                vm.open(Route.Home) // where the "Recording deleted · Undo" snackbar lives
                                rec?.let { vm.delete(Deletion.OfRecording(it)) }
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (transcript != null) {
                // Rides on top of the keyboard, so Save stays reachable while editing a title.
                BottomAppBar(Modifier.imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                context.shareTasks(selected.map { it.title.trim() to it.due })
                            },
                            enabled = selected.isNotEmpty(),
                        ) { Icon(Icons.Filled.Share, "Share to another app") }
                        FilledTonalIconButton(
                            onClick = {
                                val t = selected.single()
                                context.launchSafely(TaskIntents.calendar(t.title.trim(), transcript, t.due))
                            },
                            enabled = selected.size == 1,
                        ) { Icon(Icons.Filled.Event, "Add to calendar") }
                        Button(
                            onClick = {
                                val toSave = selected.toList()
                                vm.saveTasks(recordingId, toSave.map { it.toSuggestion() }, transcript) { count ->
                                    toSave.forEach { it.saved = true }
                                    offerDelete = count
                                }
                            },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                when (selected.size) {
                                    0 -> "Save"
                                    1 -> "Save task"
                                    else -> "Save ${selected.size} tasks"
                                }
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        offerDelete?.let { count ->
            AlertDialog(
                onDismissRequest = { offerDelete = null },
                title = { Text(if (count == 1) "Task saved" else "$count tasks saved") },
                text = { Text("Delete the recording? The transcript stays in the ${if (count == 1) "task's" else "tasks'"} notes.") },
                confirmButton = {
                    TextButton(onClick = {
                        offerDelete = null
                        vm.open(Route.Home) // where the "Recording deleted · Undo" snackbar lives
                        rec?.let { vm.delete(Deletion.OfRecording(it)) }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { offerDelete = null }) { Text("Keep") } },
            )
        }
        if (rec == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item { StatusBlock(rec, transcribing?.takeIf { it.first == rec.id }?.second, vm) }
            if (transcript != null) {
                item { TranscriptEditor(transcript) { vm.saveTranscript(recordingId, it) } }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tasks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            val whole = TaskExtractor.extract(transcript, categories = rules)
                            val title = whole.firstOrNull { it.actionable }?.title ?: whole.firstOrNull()?.title.orEmpty()
                            tasks.clear()
                            tasks.add(
                                EditableTask(
                                    title, whole.firstNotNullOfOrNull { it.due }, selected = true,
                                    categoryId = whole.firstNotNullOfOrNull { it.categoryId },
                                )
                            )
                        }) { Text("Make one task") }
                    }
                }
                items(tasks) { task ->
                    TaskEditorCard(
                        task, categories, calendarAllowed,
                        askCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                        onRemove = { tasks.remove(task) },
                    )
                }
                item {
                    TextButton(onClick = { tasks.add(EditableTask("", null, selected = true, categoryId = null)) }, Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Filled.Add, null)
                        Text("Add task", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(rec: Recording, progress: Int?, vm: MainViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val meta = buildString {
            append(formatDuration(rec.durationMs))
            if (rec.transcribeMs != null) {
                val model = rec.modelId?.let { WhisperModel.byId(it)?.label ?: it }
                append(" · transcribed in %.1fs with the %s model".format(rec.transcribeMs / 1000f, model))
            }
        }
        Text(meta, style = MaterialTheme.typography.labelMedium, color = muted)
        when (rec.status) {
            RecordingStatus.TRANSCRIBING, RecordingStatus.RECORDED -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp))
                Text(if (progress != null) "Transcribing… $progress%" else "Waiting to transcribe…")
            }
            RecordingStatus.NEEDS_MODEL -> Column {
                Text("Download a speech model in Settings, then transcribe this recording.")
                Row {
                    TextButton(onClick = { vm.open(Route.Settings) }) { Text("Open settings") }
                    TextButton(onClick = { vm.transcribe(rec.id) }) { Text("Transcribe") }
                }
            }
            RecordingStatus.NO_SPEECH -> Text("No speech was detected in this recording.", color = muted)
            RecordingStatus.FAILED -> Column {
                Text("Transcription failed: ${rec.error}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.transcribe(rec.id) }) { Text("Try again") }
            }
            RecordingStatus.DONE -> Unit
        }
    }
}

@Composable
private fun TranscriptEditor(transcript: String, onSave: (String) -> Unit) {
    var text by remember(transcript) { mutableStateOf(transcript) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Transcript") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (text != transcript) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { text = transcript }) { Text("Undo") }
                TextButton(onClick = { onSave(text) }) { Text("Save & find tasks again") }
            }
        }
    }
}

@Composable
private fun TaskEditorCard(
    task: EditableTask, categories: List<Category>, calendarAllowed: Boolean, askCalendar: () -> Unit, onRemove: () -> Unit,
) {
    var pickingDue by remember { mutableStateOf(false) }
    var pickingEvent by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    val category = categories.firstOrNull { it.id == task.categoryId }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (task.saved) {
                Icon(Icons.Filled.CheckCircle, "Saved", Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Checkbox(checked = task.selected, onCheckedChange = { task.selected = it })
            }
            TextField(
                value = task.title,
                onValueChange = { task.title = it },
                placeholder = { Text("Task") },
                enabled = !task.saved,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            )
            if (!task.saved) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
        }
        Row(Modifier.padding(start = 56.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { pickingDue = true },
                enabled = !task.saved,
                label = { Text(task.due?.label() ?: "Add date") },
                leadingIcon = { Icon(Icons.Filled.Schedule, null) },
            )
            // Only when the note said so ("every Monday…"); otherwise set it later in the task editor.
            if (task.repeatDays != Repeat.NONE) {
                AssistChip(
                    onClick = { task.repeatDays = Repeat.NONE },
                    enabled = !task.saved,
                    label = { Text(Repeat.label(task.repeatDays)) },
                    leadingIcon = { Icon(Icons.Filled.Repeat, null) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Don't repeat", Modifier.size(16.dp)) },
                )
            }
            if (categories.isNotEmpty()) {
                Box {
                    AssistChip(
                        onClick = { pickingCategory = true },
                        enabled = !task.saved,
                        label = { Text(category?.name ?: "Category") },
                        leadingIcon = {
                            if (category != null) CategoryDot(category.color) else Icon(Icons.AutoMirrored.Filled.Label, null)
                        },
                    )
                    DropdownMenu(expanded = pickingCategory, onDismissRequest = { pickingCategory = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { task.categoryId = null; pickingCategory = false })
                        categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                leadingIcon = { CategoryDot(c.color) },
                                onClick = { task.categoryId = c.id; pickingCategory = false },
                            )
                        }
                    }
                }
            }
        }
        val request = task.event
        if (request != null || task.link != null) {
            Row(Modifier.padding(start = 56.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val link = task.link
                if (link != null && link.event.recurring) {
                    FilterChip(
                        selected = link.everyTime,
                        onClick = { task.link = link.copy(everyTime = !link.everyTime) },
                        enabled = !task.saved,
                        label = { Text("Every time") },
                    )
                }
                AssistChip(
                    onClick = { if (!calendarAllowed) askCalendar() else pickingEvent = true },
                    enabled = !task.saved,
                    leadingIcon = { Icon(Icons.Filled.Event, null) },
                    label = {
                        Text(
                            when {
                                link != null -> link.describe()
                                !calendarAllowed -> "Find \"${request?.query}\" in your calendar"
                                task.eventLookedUp -> "\"${request?.query}\" not in calendar · pick"
                                else -> "Looking up \"${request?.query}\"…"
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
    if (pickingEvent) {
        EventPickerDialog(
            initialQuery = task.link?.event?.title ?: task.event?.query.orEmpty(),
            initialOffsetMinutes = task.link?.offsetMinutes ?: task.event?.offsetMinutes ?: EventTiming.DAY,
            onDismiss = { pickingEvent = false },
            onPick = { picked ->
                task.link = picked.copy(everyTime = picked.event.recurring && (task.event?.everyTime ?: false))
                task.due = EventTiming.due(picked.event, picked.offsetMinutes, java.time.ZoneId.systemDefault())
                task.repeatDays = Repeat.NONE
                pickingEvent = false
            },
        )
    }
    if (pickingDue) {
        DuePickerDialog(
            initial = task.due,
            onDismiss = { pickingDue = false },
            onConfirm = { task.due = it; pickingDue = false },
        )
    }
}

@Composable
private fun PlayButton(path: String) {
    var playing by remember { mutableStateOf(false) }
    val player = remember(path) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(path) {
        onDispose {
            player.value?.release()
            player.value = null
        }
    }
    IconButton(onClick = {
        val p = player.value ?: runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { playing = false }
            }
        }.getOrNull()?.also { player.value = it } ?: return@IconButton
        if (p.isPlaying) p.pause() else p.start()
        playing = p.isPlaying
    }) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play recording")
    }
}
