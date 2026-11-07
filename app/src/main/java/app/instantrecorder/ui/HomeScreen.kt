package app.instantrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.instantrecorder.data.Category
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.data.Settings
import app.instantrecorder.data.TaskItem
import app.instantrecorder.data.volumeGestureHint
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.EventLink
import app.instantrecorder.tasks.eventLink
import app.instantrecorder.tasks.Repeat
import app.instantrecorder.tasks.TaskIntents
import app.instantrecorder.transcribe.WhisperModel
import app.instantrecorder.trigger.VolumeKeyTriggerService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var categoryFilter by rememberSaveable { mutableStateOf<Long?>(null) } // null = all
    // An id, not the task, so the editor survives folding/unfolding and always shows the current row.
    var editingTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_RECORDINGS) }
    val openTasks = remember(tasks) { tasks.filterNot { it.done } }
    // Newest first; the list is shown grouped by the day each was finished.
    val doneTasks = remember(tasks) { tasks.filter { it.done }.sortedByDescending { it.completedAt ?: it.createdAt } }

    val snackbar = remember { SnackbarHostState() }
    // A repeating task just checked off: the pop-up saying when the next one is.
    var finishedRepeating by remember { mutableStateOf<Completion?>(null) }
    // Shows "… · Undo" for every undoable action: deletes anywhere in the app, checking a task off or back on.
    LaunchedEffect(Unit) {
        vm.undoables.collect { item ->
            if (item is Completion && item.repeating) {
                finishedRepeating?.let(vm::commit) // a second one replaces the first pop-up
                finishedRepeating = item
                return@collect
            }
            launch {
                // A newer delete takes over the snackbar, which commits the previous one.
                snackbar.currentSnackbarData?.dismiss()
                var undo = false
                try {
                    undo = snackbar.showSnackbar(item.message, "Undo", duration = SnackbarDuration.Long) ==
                        SnackbarResult.ActionPerformed
                } finally {
                    // Also reached when Home leaves composition (navigation, folding, finishing) with the snackbar up.
                    if (undo) vm.undo(item) else vm.commit(item)
                }
            }
        }
    }

    finishedRepeating?.let { item ->
        AlertDialog(
            onDismissRequest = { finishedRepeating = null; vm.commit(item) },
            title = { Text("Done!") },
            text = { Text(item.nextReminder) },
            confirmButton = { TextButton(onClick = { finishedRepeating = null; vm.commit(item) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { finishedRepeating = null; vm.undo(item) }) { Text("Undo") } },
        )
    }

    // Runs a recording action once the microphone is allowed (asking first if needed).
    var afterMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicGranted?.invoke()
        afterMicGranted = null
    }
    val withMic = { action: () -> Unit ->
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterMicGranted = action
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val editingTask = if (editingTaskId == NEW_TASK_ID) {
        TaskItem(id = NEW_TASK_ID, title = "", categoryId = categoryFilter) // starts in the category being viewed
    } else {
        tasks.firstOrNull { it.id == editingTaskId }
    }
    editingTask?.let { task ->
        val isNew = task.id == NEW_TASK_ID
        TaskEditorSheet(
            task = task,
            categories = categories,
            isNew = isNew,
            canOpenRecording = recordings.any { it.id == task.recordingId },
            onDismiss = { editingTaskId = null },
            onSave = { title, notes, due, categoryId, repeatDays, link, important ->
                if (isNew) vm.addTask(title, notes, due, categoryId, repeatDays, link, important)
                else vm.editTask(task.id, title, notes, due, categoryId, repeatDays, link, important)
                editingTaskId = null
            },
            onDelete = {
                vm.delete(Deletion.OfTask(task))
                editingTaskId = null
            },
            onOpenRecording = {
                editingTaskId = null
                task.recordingId?.let { vm.open(Route.Review(it)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instant Recorder") },
                actions = {
                    IconButton(onClick = { vm.open(Route.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            RecordButton(
                onQuickNote = { withMic(vm::toggleRecording) },
                onLongRecording = { withMic(vm::startLongRecording) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 120.dp,
            ),
        ) {
            item { SetupCard(vm) }
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == TAB_RECORDINGS, onClick = { tab = TAB_RECORDINGS }, text = { Text("Recordings", maxLines = 1) })
                    Tab(selected = tab == TAB_TASKS, onClick = { tab = TAB_TASKS }, text = { Text("Tasks (${openTasks.size})", maxLines = 1) })
                    Tab(selected = tab == TAB_DONE, onClick = { tab = TAB_DONE }, text = { Text("Done", maxLines = 1) })
                }
            }
            if (tab == TAB_RECORDINGS) {
                if (recordings.isEmpty()) {
                    item {
                        EmptyHint(
                            "No recordings yet. To record a quick note, ${settings.volumeGestureHint}, double-tap the back " +
                                "of the phone, or tap the mic. For a long recording, slide the mic up onto the lock."
                        )
                    }
                }
                items(recordings, key = { it.id }) { r ->
                    SwipeToDelete(
                        // RecordingService is working on it.
                        enabled = r.status != RecordingStatus.TRANSCRIBING,
                        onDelete = { vm.delete(Deletion.OfRecording(r)) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        RecordingRow(r, transcribing?.takeIf { it.first == r.id }?.second) { vm.open(Route.Review(r.id)) }
                    }
                }
            } else {
                val list = if (tab == TAB_TASKS) openTasks else doneTasks
                if (categories.isNotEmpty() && list.isNotEmpty()) {
                    item { CategoryFilterRow(categories, categoryFilter) { categoryFilter = it } }
                }
                val shown = if (categoryFilter == null) list else list.filter { it.categoryId == categoryFilter }
                val taskRow: @Composable (TaskItem) -> Unit = { t ->
                    SwipeToDelete(enabled = true, onDelete = { vm.delete(Deletion.OfTask(t)) }, shape = RectangleShape) {
                        TaskRow(
                            task = t,
                            category = categories.firstOrNull { it.id == t.categoryId },
                            onClick = { editingTaskId = t.id },
                            onDone = { vm.setTaskDone(t, it) },
                            onCalendar = {
                                context.launchSafely(
                                    TaskIntents.calendar(t.title, t.notes, t.dueAt?.let { Due(it, t.dueHasTime) })
                                )
                            },
                            onShare = { context.shareTasks(listOf(t.title to t.dueAt?.let { Due(it, t.dueHasTime) })) },
                        )
                    }
                }
                if (tab == TAB_TASKS) {
                    item {
                        TextButton(onClick = { editingTaskId = NEW_TASK_ID }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Text("Add task", Modifier.padding(start = 8.dp))
                        }
                    }
                    when {
                        openTasks.isEmpty() && doneTasks.isNotEmpty() ->
                            item { EmptyHint("Nothing left to do. Tasks you've checked off are in the Done tab.") }
                        openTasks.isEmpty() -> item { EmptyHint("No tasks yet. Record a note and save the tasks it finds, or add one by hand.") }
                        shown.isEmpty() -> item { EmptyHint("No tasks in this category.") }
                    }
                    items(shown, key = { it.id }) { taskRow(it) }
                } else {
                    when {
                        doneTasks.isEmpty() -> item { EmptyHint("Tasks you check off are kept here. Uncheck one to put it back in Tasks.") }
                        shown.isEmpty() -> item { EmptyHint("No finished tasks in this category.") }
                        else -> item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.delete(Deletion.OfTasks(shown)) }) {
                                    Icon(Icons.Filled.Delete, null)
                                    val inCategory = categories.firstOrNull { it.id == categoryFilter }?.let { " in ${it.name}" }.orEmpty()
                                    Text("Delete all$inCategory", Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                    val zone = ZoneId.systemDefault()
                    shown.groupBy { Instant.ofEpochMilli(it.completedAt ?: it.createdAt).atZone(zone).toLocalDate() }
                        .forEach { (day, group) ->
                            item(key = "done-$day") {
                                SectionHeader(Due(day.atStartOfDay(zone).toInstant().toEpochMilli(), hasTime = false).label())
                            }
                            items(group, key = { it.id }) { taskRow(it) }
                        }
                }
            }
        }
    }
}

/** "All" plus one chip per category; tapping the selected one goes back to All. */
@Composable
private fun CategoryFilterRow(categories: List<Category>, selected: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") }) }
        items(categories, key = { it.id }) { c ->
            FilterChip(
                selected = selected == c.id,
                onClick = { onSelect(if (selected == c.id) null else c.id) },
                label = { Text(c.name) },
                leadingIcon = { CategoryDot(c.color) },
            )
        }
    }
}

/** Shows only the steps that are still missing; disappears once everything is set up. */
@Composable
private fun SetupCard(vm: MainViewModel) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var permissionTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }

    val (hasMic, hasNotifications, triggerOn) = remember(tick, permissionTick) {
        Triple(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            VolumeKeyTriggerService.isEnabled(context),
        )
    }
    val hasModel = installed.isNotEmpty()
    if (hasMic && hasNotifications && hasModel && triggerOn) return

    OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            if (!hasMic) {
                SetupStep("Allow the microphone", "Needed to record.") {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
                }
            }
            if (!hasNotifications) {
                SetupStep("Allow notifications", "Shows the recording controls and the tasks it found.") {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                }
            }
            if (!hasModel) {
                SetupStep(
                    "Choose a speech model",
                    "A one-time download. Transcription then runs offline on this phone, and you can switch later in Settings.",
                ) {}
                val downloading = downloads.entries.firstOrNull()
                if (downloading != null) {
                    Text("Downloading the ${downloading.key.label} model… ${(downloading.value * 100).toInt()}%")
                    LinearProgressIndicator(progress = { downloading.value }, modifier = Modifier.fillMaxWidth())
                } else {
                    WhisperModel.entries.forEach { model ->
                        OutlinedButton(onClick = { vm.downloadModel(model) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                val recommended = if (model.id == Settings().modelId) " · Recommended" else ""
                                Text("${model.label} · ${model.sizeBytes / 1_000_000} MB$recommended", style = MaterialTheme.typography.titleSmall)
                                Text(model.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (!triggerOn) {
                SetupStep(
                    "Turn on the volume-button trigger",
                    "In Accessibility, open \"Instant Recorder volume-button trigger\" and switch it on.",
                ) {
                    Button(onClick = { context.launchSafely(VolumeKeyTriggerService.settingsIntent(context)) }) { Text("Open") }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(title: String, detail: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val timestampFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timestampFormat)

/** Swipe end-to-start to delete (undoable), over an error-colored background shaped like the row. */
@Composable
private fun SwipeToDelete(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    content: @Composable () -> Unit,
) {
    // Not rememberSwipeToDismissBoxState(): it's saveable, and the list keeps saved state per key, so after
    // Undo the row would come back already dismissed and fire onDismiss again.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    // onDismiss is an effect key inside SwipeToDismissBox; a stable lambda keeps it from firing twice.
    val currentOnDelete by rememberUpdatedState(onDelete)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = { currentOnDelete() },
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error, shape).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun RecordingRow(recording: Recording, progress: Int?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimestamp(recording.createdAt), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.labelLarge)
            }
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when (recording.status) {
                RecordingStatus.DONE -> Text(
                    recording.transcript.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                RecordingStatus.TRANSCRIBING -> {
                    Text("Transcribing… ${progress ?: 0}%", color = muted)
                    LinearProgressIndicator(progress = { (progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                }
                RecordingStatus.RECORDED -> Text("Waiting to transcribe", color = muted)
                RecordingStatus.NEEDS_MODEL -> Text("Download a speech model to transcribe this", color = muted)
                RecordingStatus.NO_SPEECH -> Text("No speech detected", color = muted)
                RecordingStatus.FAILED -> Text("Transcription failed: ${recording.error}", color = MaterialTheme.colorScheme.error)
            }
            if (recording.tasksCreated > 0) {
                Text(
                    if (recording.tasksCreated == 1) "1 task created" else "${recording.tasksCreated} tasks created",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskItem,
    category: Category?,
    onClick: () -> Unit,
    onDone: (Boolean) -> Unit,
    onCalendar: () -> Unit,
    onShare: () -> Unit,
) {
    val due = task.dueAt?.let { Due(it, task.dueHasTime) }
    val overdue = !task.done && task.dueAt != null && task.dueHasTime && task.dueAt < System.currentTimeMillis()
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Edit task", onClick = onClick),
        leadingContent = { Checkbox(checked = task.done, onCheckedChange = onDone) },
        headlineContent = {
            Text(task.title, textDecoration = if (task.done) TextDecoration.LineThrough else null)
        },
        supportingContent = if (due == null && category == null && task.repeatDays == Repeat.NONE && task.eventTitle == null) null else {
            {
                // Wraps to a second line instead of squeezing a label to one character per line.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    due?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (task.important) {
                                Icon(Icons.Filled.Alarm, "Important", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            }
                            Text(
                                it.label(), maxLines = 1,
                                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    category?.let { CategoryLabel(it) }
                    if (task.repeatDays != Repeat.NONE) RepeatLabel(task.repeatDays)
                    task.eventLink?.let { EventLabel(it) }
                }
            }
        },
        // Finished tasks don't need sending anywhere.
        trailingContent = if (task.done) null else {
            {
                Row {
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share to another app") }
                    IconButton(onClick = onCalendar) { Icon(Icons.Filled.Event, "Add to calendar") }
                }
            }
        },
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private val LOCK_DISTANCE = 96.dp

private const val TAB_RECORDINGS = 0
private const val TAB_TASKS = 1
private const val TAB_DONE = 2

/** editingTaskId value for "a new, unsaved task". Real ids start at 1. */
private const val NEW_TASK_ID = -1L

/**
 * The mic button. Tap: a quick note (stops after a pause or a short limit). Press and slide up onto the
 * lock: a long recording, like locking a voice message.
 */
@Composable
private fun RecordButton(onQuickNote: () -> Unit, onLongRecording: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val lockDistancePx = with(LocalDensity.current) { LOCK_DISTANCE.toPx() }
    var slidPx by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val progress = (slidPx / lockDistancePx).coerceIn(0f, 1f)
    val quickNote by rememberUpdatedState(onQuickNote)
    val longRecording by rememberUpdatedState(onLongRecording)
    val shape = FloatingActionButtonDefaults.largeShape

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Delayed, so a plain tap never flashes it; it appears while the finger stays down or slides.
        AnimatedVisibility(
            visible = pressed,
            enter = fadeIn(tween(150, delayMillis = 200)) + expandVertically(tween(150, delayMillis = 200), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(100), shrinkTowards = Alignment.Bottom),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(bottom = 8.dp).width(72.dp).height(LOCK_DISTANCE),
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(if (progress >= 1f) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null)
                    Text("Long", style = MaterialTheme.typography.labelSmall)
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, Modifier.alpha(1f - progress))
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(0, -slidPx.roundToInt()) }
                .size(96.dp)
                .shadow(6.dp, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Record"
                    onClick(label = "Record a quick note") { quickNote(); true }
                    customActions = listOf(CustomAccessibilityAction("Start a long recording") { longRecording(); true })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        var moved = false
                        var locked = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val up = down.position.y - change.position.y
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            slidPx = up.coerceIn(0f, lockDistancePx)
                            change.consume()
                            if (up >= lockDistancePx) {
                                locked = true
                                break
                            }
                        }
                        pressed = false
                        slidPx = 0f
                        when {
                            locked -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                longRecording()
                            }
                            !moved -> quickNote()
                            // Slid part-way and let go: changed their mind.
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/**
 * "📅 1 week before Ward Council on Sun, Nov 8": the meeting this task's reminder follows. A repeat icon
 * instead when a new task follows for every meeting by that name.
 */
@Composable
private fun EventLabel(link: EventLink) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            if (link.everyTime) Icons.Filled.Repeat else Icons.Filled.Event,
            contentDescription = if (link.everyTime) "Repeats for every ${link.event.title}" else null,
            Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            link.describe(), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
