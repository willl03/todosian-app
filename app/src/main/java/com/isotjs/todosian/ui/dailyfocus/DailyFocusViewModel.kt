package com.isotjs.todosian.ui.dailyfocus

import android.net.Uri
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.model.priorityRank
import com.isotjs.todosian.data.settings.DailyFocusMode
import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DailyFocusTask(
    val todo: Todo,
    val listUri: Uri,
    val listName: String,
)

data class DailyFocusUiState(
    val isLoading: Boolean = true,
    val tasks: List<DailyFocusTask> = emptyList(),

    internal val lineCache: Map<String, List<String>> = emptyMap(),
    internal val listNames: Map<String, String> = emptyMap(),
)

class DailyFocusViewModel(
    private val fileRepository: FileRepository,
    val mode: DailyFocusMode,
    val enableTasksPluginSupport: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyFocusUiState(isLoading = true))
    val uiState: StateFlow<DailyFocusUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)

    init {
        observeExternalChanges()
        refreshFromDisk(showLoading = true)
    }

    private fun observeExternalChanges() {
        val folderUri = fileRepository.getFolderUri() ?: return
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            fileRepository.observeMarkdownFilesChanges(folderUri)
                .debounce(350)
                .catch { /* best-effort */ }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun refreshFromDisk(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val today = LocalDate.now().toString()
            val listsResult = fileRepository.getAllLists(includeCounts = false)
            if (listsResult.isFailure) {
                _uiState.value = DailyFocusUiState(isLoading = false)
                return@launch
            }

            val lists = listsResult.getOrThrow()
            val tasks = mutableListOf<DailyFocusTask>()
            val lineCache = mutableMapOf<String, List<String>>()
            val listNames = mutableMapOf<String, String>()

            for (list in lists) {
                val linesResult = fileRepository.readLines(list.uri)
                if (linesResult.isFailure) continue

                val lines = linesResult.getOrThrow()
                val uriKey = list.uri.toString()
                lineCache[uriKey] = lines
                listNames[uriKey] = list.displayName

                MarkdownParser.parse(lines)
                    .filter { !it.isDone }
                    .filter { todo -> matchesFocusMode(todo, mode, today) }
                    .forEach { todo ->
                        tasks.add(
                            DailyFocusTask(
                                todo = todo,
                                listUri = list.uri,
                                listName = list.displayName,
                            ),
                        )
                    }
            }

            _uiState.value = DailyFocusUiState(
                isLoading = false,
                tasks = sortTasks(tasks),
                lineCache = lineCache,
                listNames = listNames,
            )
        }
    }

    fun toggleTodo(task: DailyFocusTask) {
        viewModelScope.launch {
            val previousLines = currentLinesFor(task)
            val newLines = MarkdownParser.tryToggleLine(
                lines = previousLines,
                lineIndex = task.todo.lineIndex,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(task.listUri, newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(task.listUri, newLines)
                if (write.isFailure) {
                    if (currentLinesFor(task) == newLines) {
                        applyLines(task.listUri, previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun editTodo(
        task: DailyFocusTask,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
    ) {
        viewModelScope.launch {
            val previousLines = currentLinesFor(task)
            val newLines = if (enableTasksPluginSupport) {
                MarkdownParser.editTodo(
                    lines = previousLines,
                    lineIndex = task.todo.lineIndex,
                    newText = newText,
                    meta = meta,
                    enableTasksPlugin = true,
                )
            } else {
                val updated = MarkdownParser.tryEditTodoText(previousLines, task.todo.lineIndex, newText)
                if (updated == null) {
                    _events.emit(Event.ShowMessage(R.string.error_read_failed))
                    refreshFromDisk(showLoading = false)
                    return@launch
                }
                updated
            }

            applyLines(task.listUri, newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(task.listUri, newLines)
                if (write.isFailure) {
                    if (currentLinesFor(task) == newLines) {
                        applyLines(task.listUri, previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun deleteTodo(task: DailyFocusTask) {
        viewModelScope.launch {
            val previousLines = currentLinesFor(task)
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, task.todo.lineIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(task.listUri, newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(task.listUri, newLines)
                if (write.isFailure) {
                    if (currentLinesFor(task) == newLines) {
                        applyLines(task.listUri, previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
    }

    private fun currentLinesFor(task: DailyFocusTask): List<String> =
        _uiState.value.lineCache[task.listUri.toString()] ?: emptyList()

    // Replicates the logic from refreshFromDisk but only for a single file, used for optimistic updates after mutations.
    private fun applyLines(listUri: Uri, newLines: List<String>) {
        val today = LocalDate.now().toString()
        val current = _uiState.value
        val uriKey = listUri.toString()

        // Rebuild the cache with the updated file's lines.
        val newCache = current.lineCache.toMutableMap()
        newCache[uriKey] = newLines

        val tasks = mutableListOf<DailyFocusTask>()
        for ((uriString, lines) in newCache) {
            val uri = uriString.toUri()
            val listName = current.listNames[uriString] ?: continue

            MarkdownParser.parse(lines)
                .filter { !it.isDone }
                .filter { todo -> matchesFocusMode(todo, mode, today) }
                .forEach { todo ->
                    tasks.add(
                        DailyFocusTask(
                            todo = todo,
                            listUri = uri,
                            listName = listName,
                        ),
                    )
                }
        }

        _uiState.value = current.copy(
            tasks = sortTasks(tasks),
            lineCache = newCache,
        )
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }
}

private fun matchesFocusMode(todo: Todo, mode: DailyFocusMode, today: String): Boolean {
    return when (mode) {
        DailyFocusMode.TODAY -> todo.dueDate == today
        DailyFocusMode.OVERDUE -> todo.dueDate?.let { it < today } == true
        DailyFocusMode.TODAY_AND_OVERDUE -> {
            val due = todo.dueDate ?: return false
            due <= today
        }
    }
}

// Sort: due date earliest -> highest priority -> list name -> line index.
private fun sortTasks(tasks: List<DailyFocusTask>): List<DailyFocusTask> {
    return tasks.sortedWith(
        compareBy<DailyFocusTask> { it.todo.dueDate == null }
            .thenBy { it.todo.dueDate ?: "" }
            .thenByDescending { it.todo.priority.priorityRank() }
            .thenBy { it.listName.lowercase() }
            .thenBy { it.todo.lineIndex },
    )
}
