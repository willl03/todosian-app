package com.isotjs.todosian.ui.category

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.MoveFolderTarget
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.ui.components.PickerRow
import com.isotjs.todosian.ui.components.buildPickerRows
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ListUiState(
    val isLoading: Boolean = false,
    val title: String = "",
    val activeTodos: List<Todo> = emptyList(),
    val completedTodos: List<Todo> = emptyList(),
    val lines: List<String> = emptyList(),
    val moveTargets: List<PickerRow<MoveTarget>>? = null,
    val isLoadingMoveTargets: Boolean = false,
    /** Folder destinations for moving this whole list; null = not loaded yet. */
    val moveFolderTargets: List<MoveFolderTarget>? = null,
)

data class MoveTarget(
    val title: String,
    val uri: Uri,
)

class CategoryViewModel(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
    /** Order-file key of the containing folder: the folder name, "" for unfiled. */
    private val parentPath: String = "",
    /** Document URI of the containing folder (vault root for unfiled); the move source. */
    private val parentFolderUri: Uri? = null,
    /** List name from the pager scan; seeds the title before the file is read. */
    private val seededTitle: String = "",
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListUiState(isLoading = true, title = seededTitle))
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)
    private var lastScanAtMs = 0L

    init {
        observeExternalChanges()
        refreshFromDisk(showLoading = true)
    }

    private fun observeExternalChanges() {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            fileRepository.observeDocumentChanges(categoryUri)
                .debounce(250)
                .catch {
                    // TODO: Pull to refresh.
                }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun refreshFromDisk(showLoading: Boolean = false, skipIfFresh: Boolean = false) {
        if (skipIfFresh && System.currentTimeMillis() - lastScanAtMs < RESCAN_MIN_INTERVAL_MS) return
        lastScanAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val nameResult = fileRepository.getDisplayName(categoryUri)
            val title = nameResult.getOrNull()?.removeSuffix(".md").orEmpty()

            val linesResult = fileRepository.readLines(categoryUri)
            if (linesResult.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                _uiState.value = _uiState.value.copy(isLoading = false, title = title)
                return@launch
            }

            val lines = linesResult.getOrThrow()
            if (!showLoading && lines == _uiState.value.lines) return@launch

            val todos = MarkdownParser.parse(lines)
            val (completed, active) = todos.partition { it.isDone }

            _uiState.value = ListUiState(
                isLoading = false,
                title = title,
                activeTodos = active.sortedBy { it.lineIndex },
                completedTodos = completed.sortedBy { it.lineIndex },
                lines = lines,
                moveTargets = _uiState.value.moveTargets,
            )
        }
    }

    fun loadMoveTargets() {
        val state = _uiState.value
        if (state.moveTargets != null || state.isLoadingMoveTargets) return

        _uiState.value = state.copy(isLoadingMoveTargets = true)
        viewModelScope.launch {
            val content = fileRepository.getVaultContent(includeCounts = false).getOrNull()
            val targets = if (content == null) {
                emptyList()
            } else {
                val rows = buildPickerRows(
                    folders = content.folders.map { folder ->
                        folder.displayName to folder.lists
                            .filterNot { it.uri == categoryUri }
                            .map { MoveTarget(title = it.displayName, uri = it.uri) }
                    },
                    unfiledLists = content.unfiledLists
                        .filterNot { it.uri == categoryUri }
                        .map { MoveTarget(title = it.displayName, uri = it.uri) },
                )
                // Folder headers whose entries were all filtered out (e.g. a folder holding
                // only the section the todo is moved from) would render as empty groups.
                rows.filterIndexed { index, row ->
                    when (row) {
                        is PickerRow.ListRow -> true
                        is PickerRow.FolderHeader -> rows.getOrNull(index + 1) is PickerRow.ListRow
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                moveTargets = targets,
                isLoadingMoveTargets = false,
            )
        }
    }

    /**
     * Renames this list from inside the pager. The rename changes the document URI; the pager
     * re-scans and rebuilds the page, so no local refresh is attempted here.
     */
    fun renameCategory(newName: String) {
        viewModelScope.launch {
            val currentName = _uiState.value.title
            val result = fileRepository.renameCategory(categoryUri, parentPath, currentName, newName)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            }
        }
    }

    /** Deletes this list; the pager drops the page on the next vault re-scan. */
    fun deleteCategory() {
        viewModelScope.launch {
            val result = fileRepository.deleteCategory(categoryUri)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                _events.emit(Event.CategoryDeleted)
            }
        }
    }

    fun loadMoveFolderTargets() {
        val state = _uiState.value
        if (state.moveFolderTargets != null) return

        viewModelScope.launch {
            val content = fileRepository.getVaultContent(includeCounts = false).getOrNull()
            val rootUri = fileRepository.getFolderUri()
            val targets = buildList {
                // Unfiled is a destination for every folder's lists, but not for unfiled itself.
                if (parentPath.isNotEmpty() && !content?.unfiledLists.isNullOrEmpty()) {
                    add(MoveFolderTarget(displayName = "", uri = rootUri ?: Uri.EMPTY, isUnfiled = true))
                }
                content?.folders?.forEach { folder ->
                    if (folder.uri != parentFolderUri) {
                        add(MoveFolderTarget(folder.displayName, folder.uri))
                    }
                }
            }
            _uiState.value = _uiState.value.copy(moveFolderTargets = targets)
        }
    }

    /** Moves this whole list to another folder; the pager drops the page on the next re-scan. */
    fun moveCategory(target: MoveFolderTarget) {
        viewModelScope.launch {
            val source = parentFolderUri
            val result = if (source == null) {
                Result.failure(IllegalStateException("No source folder"))
            } else {
                fileRepository.moveCategory(categoryUri = categoryUri, sourceParentUri = source, targetFolderUri = target.uri)
            }
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                _events.emit(Event.ShowMessage(R.string.folder_move_list_success))
            }
            _uiState.value = _uiState.value.copy(moveFolderTargets = null)
        }
    }

    fun toggleTodo(todo: Todo, enableTasksPluginSupport: Boolean) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.tryToggleLine(
                lines = previousLines,
                lineIndex = todo.lineIndex,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun addTodo(
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
        addAtStart: Boolean = false,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.addTodo(
                lines = previousLines,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
                addAtStart = addAtStart,
            )
            if (newLines == previousLines) return@launch

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun addSubTodo(
        parent: Todo,
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.addSubTodo(
                lines = previousLines,
                parentLineIndex = parent.lineIndex,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun editTodo(
        todo: Todo,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = if (enableTasksPluginSupport) {
                MarkdownParser.editTodo(
                    lines = previousLines,
                    lineIndex = todo.lineIndex,
                    newText = newText,
                    meta = meta,
                    enableTasksPlugin = true,
                )
            } else {
                val updated = MarkdownParser.tryEditTodoText(previousLines, todo.lineIndex, newText)
                if (updated == null) {
                    _events.emit(Event.ShowMessage(R.string.error_read_failed))
                    refreshFromDisk(showLoading = false)
                    return@launch
                }
                updated
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun deleteTodo(todo: Todo) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, todo.lineIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun moveTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            if (todo.indentLevel > 0) {
                _events.emit(Event.ShowMessage(R.string.category_move_subtask_disabled))
                return@launch
            }
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, todo.lineIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.moveTodoLine(categoryUri, targetUri, todo.lineIndex)
                if (result.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_move_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun copyTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.copyTodoLine(categoryUri, targetUri, todo.lineIndex)
                if (result.isFailure) {
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_copy_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }

    private fun applyLines(lines: List<String>) {
        val todos = MarkdownParser.parse(lines)
        val (completed, active) = todos.partition { it.isDone }
        _uiState.value = _uiState.value.copy(
            activeTodos = active.sortedBy { it.lineIndex },
            completedTodos = completed.sortedBy { it.lineIndex },
            lines = lines,
            moveTargets = _uiState.value.moveTargets,
        )
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
        /** The section was deleted; the UI should leave this screen (folder view takes over). */
        data object CategoryDeleted : Event
    }

    private companion object {
        /** A STARTED re-read this soon after the previous one is skipped. */
        private const val RESCAN_MIN_INTERVAL_MS = 2_000L
    }
}
