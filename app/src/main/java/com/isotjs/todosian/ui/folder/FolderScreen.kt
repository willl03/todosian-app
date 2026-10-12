package com.isotjs.todosian.ui.folder

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Folder
import com.isotjs.todosian.data.model.MoveFolderTarget
import com.isotjs.todosian.data.model.TodoList
import com.isotjs.todosian.ui.components.DragReorderIndicatorSlot
import com.isotjs.todosian.ui.components.DragReorderState
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.dragReorderable
import com.isotjs.todosian.ui.components.validateFolderName
import com.isotjs.todosian.ui.components.validateListFileName
import com.isotjs.todosian.ui.components.todosianFabShape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import kotlinx.coroutines.FlowPreview

data class FolderUiState(
    val isLoading: Boolean = true,
    val folderName: String = "",
    val lists: List<TodoList> = emptyList(),
    /** null = not loaded yet; used by the "move list to another folder" sheet. */
    val moveFolderTargets: List<MoveFolderTarget>? = null,
)

class FolderViewModel(
    private val fileRepository: FileRepository,
    private val folderUri: Uri,
    val isUnfiled: Boolean,
    /** Order-file key for this folder's lists: the folder name, "" for unfiled. */
    private val folderPath: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderUiState())
    val uiState: StateFlow<FolderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch { doRefresh(showLoading) }
    }

    /** Awaitable refresh for pull-to-refresh: completes when the re-scan is done. */
    suspend fun refreshNow(showLoading: Boolean = false) = doRefresh(showLoading)

    private suspend fun doRefresh(showLoading: Boolean) {
        run {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val folderName = if (isUnfiled) "" else {
                fileRepository.getFolderDisplayName(folderUri).getOrNull().orEmpty()
            }
            val listsResult = fileRepository.getListsIn(folderUri, folderPath)

            _uiState.value = FolderUiState(
                isLoading = false,
                folderName = folderName,
                lists = listsResult.getOrDefault(_uiState.value.lists),
                moveFolderTargets = _uiState.value.moveFolderTargets,
            )
            if (listsResult.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
            }
        }
    }

    fun createList(name: String) {
        viewModelScope.launch {
            val created = fileRepository.createList(folderUri, name)
            if (created.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh(showLoading = false)
        }
    }

    fun renameList(listUri: Uri, currentName: String, newName: String) {
        viewModelScope.launch {
            val result = fileRepository.renameList(listUri, folderPath, currentName, newName)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh(showLoading = false)
        }
    }

    fun deleteList(listUri: Uri) {
        viewModelScope.launch {
            val result = fileRepository.deleteList(listUri)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh(showLoading = false)
        }
    }

    fun loadMoveFolderTargets() {
        val state = _uiState.value
        if (state.moveFolderTargets != null) return

        viewModelScope.launch {
            val content = fileRepository.getVaultContent().getOrNull()
            val targets = buildList {
                if (!isUnfiled && content?.unfiledLists?.isNotEmpty() == true) {
                    add(
                        MoveFolderTarget(
                            displayName = "", // resolved to a localized string by the UI
                            uri = fileRepository.getFolderUri() ?: Uri.EMPTY,
                            isUnfiled = true,
                        ),
                    )
                }
                content?.folders?.forEach { folder ->
                    if (folder.uri != folderUri) {
                        add(MoveFolderTarget(folder.displayName, folder.uri))
                    }
                }
            }
            _uiState.value = _uiState.value.copy(moveFolderTargets = targets)
        }
    }

    /** Renames this folder from inside the pager; the pager rescans pages on success. */
    fun renameFolder(newName: String) {
        viewModelScope.launch {
            val currentName = _uiState.value.folderName.ifBlank { folderPath }
            val result = fileRepository.renameFolder(folderUri, currentName, newName)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                _events.emit(Event.FolderRenamed)
            }
        }
    }

    /** Deletes this folder (and its lists) from inside the pager; the UI backs out on success. */
    fun deleteFolder() {
        viewModelScope.launch {
            val result = fileRepository.deleteFolder(folderUri)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                _events.emit(Event.FolderDeleted)
            }
        }
    }

    fun moveList(listUri: Uri, targetFolder: MoveFolderTarget) {
        viewModelScope.launch {
            val result = fileRepository.moveList(
                listUri = listUri,
                sourceParentUri = folderUri,
                targetFolderUri = targetFolder.uri,
            )
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                _events.emit(Event.ShowMessage(R.string.folder_move_list_success))
            }
            _uiState.value = _uiState.value.copy(moveFolderTargets = null)
            refresh(showLoading = false)
        }
    }

    /**
     * Finalizes a drag: moves the list [fromIndex] → [toIndex] in the visible order and
     * persists the new preference (the complete set of existing lists in this folder, which
     * also prunes deleted entries from the order file). On failure the order reverts via a
     * fresh scan.
     */
    fun dropList(fromIndex: Int, toIndex: Int) {
        val lists = _uiState.value.lists.toMutableList()
        if (fromIndex !in lists.indices || toIndex !in lists.indices) return

        val list = lists.removeAt(fromIndex)
        lists.add(toIndex, list)
        _uiState.value = _uiState.value.copy(lists = lists)

        viewModelScope.launch {
            val names = lists.map { it.displayName }
            val result = fileRepository.reorderLists(folderPath, names)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_reorder_failed))
                refresh(showLoading = false)
            }
        }
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
        data object FolderRenamed : Event
        data object FolderDeleted : Event
    }
}

data class FolderPagerUiState(
    val isLoading: Boolean = true,
    /** All folders in swipe order: A–Z, "Unfiled lists" as the last page. */
    val pages: List<Folder> = emptyList(),
)

/**
 * Folder pager. [entryFolderUri]/[entryIsUnfiled] identify the folder to land on; swiping
 * left/right moves between sibling folders, "Unfiled lists" being the rightmost page.
 *
 * Pages are snapshotted once at entry and intentionally not re-scanned: nothing on this screen
 * can add or remove folders, so the pager badge (e.g. 2/5) stays stable for the whole visit.
 * Moving the last list out of "Unfiled lists" therefore keeps showing that page (as an empty
 * state) until the user backs out; the next entry picks up the new layout. The lists inside
 * each page stay live through the page's own [FolderViewModel].
 *
 * Observes the vault **once** for the whole pager (not per page): every vault change is
 * re-broadcast on [vaultChanges] and each visible page refreshes itself from it. This keeps
 * ContentObserver registrations and refresh work independent of how many pages were visited.
 */
class FolderPagerViewModel(
    private val fileRepository: FileRepository,
    private val entryFolderUri: Uri,
    private val entryIsUnfiled: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderPagerUiState())
    val uiState: StateFlow<FolderPagerUiState> = _uiState.asStateFlow()

    private val vaultChangesInternal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val vaultChanges: SharedFlow<Unit> = vaultChangesInternal.asSharedFlow()

    /** Whether the unfiled page belongs to this visit; frozen after the first scan. */
    private var includeUnfiled: Boolean? = null

    init {
        observeVaultChanges()
        viewModelScope.launch { scanPages() }
    }

    /**
     * Re-scans the vault and rebuilds the pages (same set, fresh URIs/names) — used after a
     * folder rename inside the pager, which changes the folder's document URI. The page count
     * and the unfiled inclusion stay frozen, so the badge does not jump.
     */
    fun rescanPages() {
        viewModelScope.launch { scanPages() }
    }

    private suspend fun scanPages() {
        val content = fileRepository.getVaultContent().getOrNull()
        val rootUri = fileRepository.getFolderUri()
        if (content == null || rootUri == null) {
            _uiState.value = FolderPagerUiState(isLoading = false)
            return
        }

        // "Unfiled lists" is the rightmost page; only present when it holds lists (or when
        // the user is currently inside it, e.g. to empty it by moving the last list out).
        val include = includeUnfiled ?: (content.unfiledLists.isNotEmpty() || entryIsUnfiled)
        includeUnfiled = include

        val pages = content.folders.toMutableList()
        if (include) {
            pages.add(
                Folder(
                    displayName = "",
                    uri = rootUri,
                    lists = content.unfiledLists,
                    isUnfiled = true,
                ),
            )
        }
        _uiState.value = FolderPagerUiState(isLoading = false, pages = pages)
    }

    private fun observeVaultChanges() {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            val rootUri = fileRepository.getFolderUri() ?: return@launch
            fileRepository.observeMarkdownFilesChanges(rootUri)
                .debounce(250)
                .catch { /* best-effort */ }
                .collectLatest {
                    vaultChangesInternal.tryEmit(Unit)
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    fileRepository: FileRepository,
    folderUri: Uri,
    isUnfiled: Boolean,
    onOpenList: (Uri) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerViewModel: FolderPagerViewModel = viewModel(
        factory = FolderPagerViewModelFactory(fileRepository, folderUri, isUnfiled),
    )
    val uiState by pagerViewModel.uiState.collectAsStateWithLifecycle()

    val pages = uiState.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // First time the pages arrive, land on the requested folder.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(pages, landed) {
        if (!landed && pages.isNotEmpty()) {
            val index = pages.indexOfFirst { it.uri == folderUri }
            if (index >= 0) pagerState.scrollToPage(index)
            landed = true
        }
    }

    if (uiState.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TodosianLoadingIndicator()
        }
        return
    }

    if (pages.isEmpty()) {
        // The requested folder no longer exists (deleted/renamed externally since navigation).
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_navigate_back),
                            )
                        }
                    },
                )
            },
            modifier = modifier.fillMaxSize(),
        ) { padding ->
            TodosianEmptyState(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.folder_unavailable_title),
                subtitle = stringResource(R.string.folder_unavailable_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
        return
    }

    HorizontalPager(
        state = pagerState,
        key = { pageIndex -> pages.getOrNull(pageIndex)?.uri ?: pageIndex },
        modifier = modifier.fillMaxSize(),
    ) { pageIndex ->
        val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
        FolderPage(
            fileRepository = fileRepository,
            folder = page,
            onOpenList = onOpenList,
            onBack = onBack,
            folderPosition = pageIndex + 1,
            folderCount = pages.size,
            vaultChanges = pagerViewModel.vaultChanges,
            onRescanPages = { pagerViewModel.rescanPages() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** One folder page inside the folder pager: its lists, their actions and the new-list FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPage(
    fileRepository: FileRepository,
    folder: Folder,
    onOpenList: (Uri) -> Unit,
    onBack: () -> Unit,
    folderPosition: Int,
    folderCount: Int,
    vaultChanges: SharedFlow<Unit>,
    onRescanPages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUnfiled = folder.isUnfiled
    val viewModel: FolderViewModel = viewModel(
        key = "folder_${folder.uri}",
        factory = FolderViewModelFactory(fileRepository, folder.uri, isUnfiled, folder.displayName),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refresh(showLoading = false)
        }
    }

    // Vault changes (sync, Obsidian edits) refresh this page; the single ContentObserver
    // registration lives in the pager's ViewModel.
    LaunchedEffect(viewModel, vaultChanges) {
        vaultChanges.collect {
            viewModel.refresh(showLoading = false)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                FolderViewModel.Event.FolderRenamed -> onRescanPages()
                FolderViewModel.Event.FolderDeleted -> onBack()
                else -> Unit
            }
        }
    }

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is FolderViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
                else -> Unit // FolderRenamed/FolderDeleted are handled in the collector above.
            }
        }
    }

    val scope = rememberCoroutineScope()

    var showNewListSheet by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<TodoList?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<TodoList?>(null) }
    var moveListTarget by remember { mutableStateOf<TodoList?>(null) }

    var showRenameFolderSheet by remember { mutableStateOf(false) }
    var renameFolderValue by remember { mutableStateOf("") }
    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var folderMenuExpanded by remember(folder.uri) { mutableStateOf(false) }

    val newListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val renameListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moveListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showNewListSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showNewListSheet = false
                newListName = ""
            },
            sheetState = newListSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.folder_new_list_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.folder_new_list_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                newListSheetState.hide()
                                showNewListSheet = false
                                newListName = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.createList(newListName)
                            scope.launch {
                                newListSheetState.hide()
                                showNewListSheet = false
                                newListName = ""
                            }
                        },
                        enabled = newListName.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        val validation = remember(renameValue) { validateListFileName(renameValue) }
        val errorText = validation.errorMessageResId?.let { stringResource(it) }
        ModalBottomSheet(
            onDismissRequest = {
                renameTarget = null
                renameValue = ""
            },
            sheetState = renameListSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.folder_rename_list_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.folder_rename_list_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                renameListSheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val target = renameTarget
                            val normalized = validation.normalizedName
                            if (target != null && normalized != null) {
                                viewModel.renameList(target.uri, currentName = target.displayName, newName = normalized)
                            }
                            scope.launch {
                                renameListSheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                        enabled = validation.normalizedName != null,
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = stringResource(R.string.folder_delete_list_title)) },
            text = {
                Text(
                    text = stringResource(R.string.folder_delete_list_body),
                    textAlign = TextAlign.Start,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget
                        if (target != null) viewModel.deleteList(target.uri)
                        deleteTarget = null
                    },
                ) {
                    Text(text = stringResource(R.string.folder_delete_list_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (moveListTarget != null) {
        LaunchedEffect(moveListTarget) {
            viewModel.loadMoveFolderTargets()
        }
        ModalBottomSheet(
            onDismissRequest = { moveListTarget = null },
            sheetState = moveListSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.folder_move_list_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.folder_move_list_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val targets = uiState.moveFolderTargets
                when {
                    targets == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TodosianLoadingIndicator()
                        }
                    }

                    targets.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.folder_move_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        targets.forEach { target ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (target.isUnfiled) {
                                            stringResource(R.string.home_unfiled_title)
                                        } else {
                                            target.displayName
                                        },
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (target.isUnfiled) {
                                            Icons.Outlined.Unarchive
                                        } else {
                                            Icons.Outlined.Folder
                                        },
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val list = moveListTarget
                                    if (list != null) {
                                        viewModel.moveList(list.uri, target)
                                    }
                                    moveListTarget = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRenameFolderSheet) {
        val validation = remember(renameFolderValue) { validateFolderName(renameFolderValue) }
        val errorText = validation.errorMessageResId?.let { stringResource(it) }
        ModalBottomSheet(
            onDismissRequest = {
                showRenameFolderSheet = false
                renameFolderValue = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_rename_folder_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = renameFolderValue,
                    onValueChange = { renameFolderValue = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.home_rename_folder_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                showRenameFolderSheet = false
                                renameFolderValue = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.renameFolder(renameFolderValue)
                            scope.launch { showRenameFolderSheet = false; renameFolderValue = "" }
                        },
                        enabled = validation.normalizedName != null,
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (showDeleteFolderDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = false },
            title = { Text(text = stringResource(R.string.home_delete_folder_title)) },
            text = {
                Text(
                    text = stringResource(R.string.home_delete_folder_body),
                    textAlign = TextAlign.Start,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder()
                        showDeleteFolderDialog = false
                    },
                ) {
                    Text(text = stringResource(R.string.home_delete_folder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderDialog = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isUnfiled) {
                            stringResource(R.string.home_unfiled_title)
                        } else {
                            uiState.folderName
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
                actions = {
                    Text(
                        text = stringResource(R.string.page_indicator, folderPosition, folderCount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    if (!isUnfiled) {
                        Box {
                            IconButton(onClick = { folderMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.cd_overflow_menu),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = folderMenuExpanded,
                                onDismissRequest = { folderMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.home_folder_action_rename)) },
                                    onClick = {
                                        folderMenuExpanded = false
                                        renameFolderValue = uiState.folderName
                                        showRenameFolderSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.home_folder_action_delete)) },
                                    onClick = {
                                        folderMenuExpanded = false
                                        showDeleteFolderDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewListSheet = true },
                shape = todosianFabShape(),
                // See HomeScreen: concave shadow tessellation freezes the RenderThread.
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_list),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        var isRefreshing by remember { mutableStateOf(false) }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    viewModel.refreshNow(showLoading = false)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TodosianLoadingIndicator()
                }
            }

            uiState.lists.isEmpty() -> {
                TodosianEmptyState(
                    icon = Icons.Outlined.Checklist,
                    title = stringResource(R.string.folder_empty_title),
                    subtitle = stringResource(R.string.folder_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
        val listState = rememberLazyListState()
        val dragState = remember(listState) {
            DragReorderState(
                listState = listState,
                scope = scope,
                onDrop = { from, to -> viewModel.dropList(from, to) },
                reorderableCount = { uiState.lists.size },
            )
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) {
            itemsIndexed(
                items = uiState.lists,
                key = { _, list -> list.uri.toString() },
            ) { listIndex, list ->
                val rowKey = list.uri.toString()
                Column(modifier = Modifier.animateItem(
                    fadeInSpec = tween(180),
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    fadeOutSpec = tween(160),
                )) {
                    DragReorderIndicatorSlot(dragState, rowKey)
                    TodoListRow(
                        list = list,
                        onClick = { onOpenList(list.uri) },
                        onRename = {
                            renameTarget = list
                            renameValue = list.displayName
                        },
                        onMove = { moveListTarget = list },
                        onDelete = { deleteTarget = list },
                        onMoveUp = listIndex.takeIf { it > 0 }?.let { from ->
                            { viewModel.dropList(from, from - 1) }
                        },
                        onMoveDown = listIndex.takeIf { it < uiState.lists.lastIndex }?.let { from ->
                            { viewModel.dropList(from, from + 1) }
                        },
                        modifier = Modifier.dragReorderable(dragState, rowKey, MaterialTheme.shapes.extraLarge),
                    )
                    DragReorderIndicatorSlot(dragState, rowKey, below = true)
                }
            }
        }
        }
        }
        }
    }
}

@Composable
private fun TodoListRow(
    list: TodoList,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides the action; non-null moves the row by one position. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    var menuExpanded by remember(list.uri) { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                text = list.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(text = "${list.doneCount}/${list.todoCount}")
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Checklist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_overflow_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.folder_list_action_rename)) },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    if (onMoveUp != null) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.folder_list_action_move_up)) },
                            leadingIcon = { Icon(imageVector = Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onMoveUp()
                            },
                        )
                    }
                    if (onMoveDown != null) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.folder_list_action_move_down)) },
                            leadingIcon = { Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onMoveDown()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.folder_list_action_move)) },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.DriveFileMove, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.folder_list_action_delete)) },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

private data class RenameListValidation(
    val normalizedFileName: String?,
    @param:StringRes val errorMessageResId: Int?,
)

private fun validateListNameForRename(input: String): RenameListValidation {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return RenameListValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_folder_error_empty,
        )
    }

    if (trimmed.contains("sync-conflict", ignoreCase = true)) {
        return RenameListValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_folder_error_sync_conflict,
        )
    }

    val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    if (trimmed.any { it in invalidChars }) {
        return RenameListValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_folder_error_invalid_chars,
        )
    }

    val base = trimmed
        .removeSuffix(".md")
        .removeSuffix(".MD")
        .removeSuffix(".Md")
        .removeSuffix(".mD")
        .trim()
    if (base.isEmpty()) {
        return RenameListValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_folder_error_empty,
        )
    }

    return RenameListValidation(
        normalizedFileName = "$base.md",
        errorMessageResId = null,
    )
}

private class FolderViewModelFactory(
    private val fileRepository: FileRepository,
    private val folderUri: Uri,
    private val isUnfiled: Boolean,
    private val folderPath: String,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FolderViewModel(fileRepository, folderUri, isUnfiled, folderPath) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

private class FolderPagerViewModelFactory(
    private val fileRepository: FileRepository,
    private val entryFolderUri: Uri,
    private val entryIsUnfiled: Boolean,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolderPagerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FolderPagerViewModel(fileRepository, entryFolderUri, entryIsUnfiled) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
