package com.isotjs.todosian.ui.category

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.model.Folder
import com.isotjs.todosian.data.model.VaultContent
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.NavSeed
import com.isotjs.todosian.ui.components.FolderColorPickerSheet
import com.isotjs.todosian.ui.components.FolderMenuItems
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.pickedColorToName
import com.isotjs.todosian.ui.components.validateFolderName
import kotlinx.coroutines.FlowPreview
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

data class ListPagerUiState(
    val isLoading: Boolean = true,
    /** The lists of the containing folder, in pager order. */
    val pages: List<Category> = emptyList(),
    /** Order-file key of the containing folder: the folder name, "" for unfiled. */
    val parentPath: String = "",
    /** Document URI of the containing folder (vault root for unfiled). */
    val parentFolderUri: Uri? = null,
    /** Live folder display name (may have been renamed since [parentPath] was seeded). */
    val folderName: String = "",
    /** One-shot: a newly created section the pager should scroll to (add-from-menu). */
    val pendingJumpUri: String? = null,
)

/**
 * Swipeable pager over a folder's sections. Resolves the folder containing [categoryUri] itself,
 * so widget intents can route straight into a section without knowing its folder. A fixed folder
 * bar (name, folder menu) stays on top while the pages — each a [CategoryScreen] with its own
 * compact section bar — swipe underneath.
 *
 * Same logic as the folder pager: the view model pins the FOLDER (stable across section
 * renames, re-pinned by name across folder renames) and every scan rebuilds the pages from
 * the fresh listing. The visible POSITION is the source of truth — no per-section anchoring.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListPagerScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    categoryUri: Uri,
    onBack: () -> Unit,
    onOpenFolderView: (Uri) -> Unit,
    /** Called when the visible section deletes itself; the UI moves to the folder view. */
    onCategoryDeleted: (folderUri: Uri, isUnfiled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    autoOpenAddTodo: Boolean = false,
) {
    val pagerViewModel: ListPagerViewModel = viewModel(
        factory = ListPagerViewModelFactory(fileRepository, categoryUri),
    )
    val uiState by pagerViewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerViewModel) {
        pagerViewModel.events.collectLatest { event ->
            when (event) {
                is ListPagerViewModel.Event.ShowMessage ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                ListPagerViewModel.Event.FolderDeleted -> onBack()
            }
        }
    }

    val pages = uiState.pages
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pages.size },
    )

    // Land once on the requested section (deep link / seed); after that the pager's position
    // is preserved across re-scans, exactly like the folder pager.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(pages, landed) {
        if (!landed && pages.isNotEmpty()) {
            val index = pages.indexOfFirst { it.uri == categoryUri }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                landed = true
            }
        }
    }

    // One-shot jump to a section created from the folder menu (the pager follows it).
    LaunchedEffect(pages, uiState.pendingJumpUri) {
        val target = uiState.pendingJumpUri ?: return@LaunchedEffect
        val index = pages.indexOfFirst { it.uri.toString() == target }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            pagerViewModel.jumpConsumed()
        }
    }

    // Folder menu state (shared for the fixed bar's overflow menu).
    var folderMenuExpanded by remember { mutableStateOf(false) }
    var showRenameFolderSheet by remember { mutableStateOf(false) }
    var renameFolderValue by remember { mutableStateOf("") }
    var showColorPicker by remember { mutableStateOf(false) }
    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var showAddSectionSheet by remember { mutableStateOf(false) }
    var addSectionName by remember { mutableStateOf("") }

    if (uiState.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TodosianLoadingIndicator()
        }
        return
    }

    if (pages.isEmpty()) {
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
                icon = Icons.Outlined.Checklist,
                title = stringResource(R.string.list_unavailable_title),
                subtitle = stringResource(R.string.list_unavailable_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
        return
    }

    val folderTitle = uiState.folderName.ifBlank {
        stringResource(R.string.home_unfiled_title)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = folderTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.page_indicator,
                                pagerState.currentPage + 1,
                                pages.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    // Unfiled sections have no folder to act on.
                    if (uiState.folderName.isNotBlank()) {
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
                                FolderMenuItems(
                                    onAddSection = { showAddSectionSheet = true },
                                    onRename = {
                                        renameFolderValue = uiState.folderName
                                        showRenameFolderSheet = true
                                    },
                                    onPickColor = { showColorPicker = true },
                                    onDelete = { showDeleteFolderDialog = true },
                                    onReorder = {
                                        uiState.parentFolderUri?.let(onOpenFolderView)
                                    },
                                    onDismiss = { folderMenuExpanded = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            key = { pageIndex -> pages.getOrNull(pageIndex)?.uri ?: pageIndex },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { pageIndex ->
            val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
            CategoryScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                categoryUri = page.uri,
                autoOpenAddTodo = autoOpenAddTodo && page.uri == categoryUri,
                parentPath = uiState.parentPath,
                parentFolderUri = uiState.parentFolderUri,
                seededTitle = page.displayName,
                onCategoryDeleted = {
                    uiState.parentFolderUri?.let { folderUri ->
                        onCategoryDeleted(folderUri, uiState.parentPath.isBlank())
                    }
                },
                onCategoryRenamed = { pagerViewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showAddSectionSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddSectionSheet = false
                addSectionName = ""
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
                    text = stringResource(R.string.folder_new_list_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = addSectionName,
                    onValueChange = { addSectionName = it },
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
                            showAddSectionSheet = false
                            addSectionName = ""
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            pagerViewModel.createSection(addSectionName)
                            showAddSectionSheet = false
                            addSectionName = ""
                        },
                        enabled = addSectionName.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
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
                            showRenameFolderSheet = false
                            renameFolderValue = ""
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            pagerViewModel.renameFolder(renameFolderValue)
                            showRenameFolderSheet = false
                            renameFolderValue = ""
                        },
                        enabled = validation.normalizedName != null,
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (showColorPicker) {
        FolderColorPickerSheet(
            folderName = uiState.folderName.ifBlank { uiState.parentPath },
            onPick = { emoji ->
                pagerViewModel.setFolderColor(emoji)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }

    if (showDeleteFolderDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = false },
            title = { Text(text = stringResource(R.string.home_delete_folder_title)) },
            text = {
                Text(
                    text = stringResource(R.string.home_delete_folder_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pagerViewModel.deleteFolder()
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
}

class ListPagerViewModel(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListPagerUiState())
    val uiState: StateFlow<ListPagerUiState> = _uiState.asStateFlow()

    sealed interface Event {
        data class ShowMessage(@StringRes val messageResId: Int) : Event
        data object FolderDeleted : Event
    }

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * The pinned folder. Resolved on the first scan from the entry section (or the seed),
     * then tracked by URI — which is stable across SECTION renames. Only a FOLDER rename
     * invalidates it, and the display name (also re-tracked on every scan) is the fallback.
     */
    private var pinnedFolderUri: Uri? = null
    private var pinnedFolderName: String? = null
    private var pinnedIsUnfiled = false

    /** Guards `refresh()`: only the most recently started scan may apply its result. */
    private var scanGeneration = 0L

    init {
        observeVaultChanges()
        // Instant first frame: the sending screen knows this folder's lists; the scan below
        // reconciles — the seed is a one-shot navigation argument.
        val seed = NavSeed.consumeListPager(categoryUri)
        if (seed != null) {
            pinnedFolderUri = seed.parentFolderUri
            pinnedFolderName = seed.parentPath
            pinnedIsUnfiled = seed.parentPath.isBlank()
            _uiState.value = ListPagerUiState(
                isLoading = false,
                pages = seed.lists,
                parentPath = seed.parentPath,
                parentFolderUri = seed.parentFolderUri,
                folderName = seed.parentPath,
            )
        }
        viewModelScope.launch { refresh() }
    }

    private fun observeVaultChanges() {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            val rootUri = fileRepository.getFolderUri() ?: return@launch
            fileRepository.observeMarkdownFilesChanges(rootUri)
                .debounce(250)
                .catch { /* best-effort */ }
                .collectLatest {
                    refresh()
                }
        }
    }

    /**
     * Re-scans the vault and rebuilds the pages from the fresh listing — the same logic as
     * the folder pager. The visible pager position never moves; pages re-key onto their
     * fresh URIs in place, so renames update the visible section without any anchoring.
     * Several scans can be in flight at once (rename events, the debounced file observer);
     * a generation counter makes the last-STARTED scan the only one allowed to apply — a
     * scan that read the listing before a rename must never apply stale pages (they would
     * re-key the visible page onto a dead URI: "could not read file" + the title rolling
     * back).
     */
    fun refresh() {
        val generation = ++scanGeneration
        viewModelScope.launch {
            val content = fileRepository.getVaultContent(includeCounts = false).getOrNull()
            if (generation != scanGeneration) return@launch
            if (content == null) return@launch // keep the current state on a failed read

            val rootUri = fileRepository.getFolderUri()
            val folder: Folder? = when {
                pinnedIsUnfiled -> null
                pinnedFolderUri != null ->
                    content.folders.firstOrNull { it.uri == pinnedFolderUri }
                        ?: content.folders.firstOrNull {
                            pinnedFolderName != null && it.displayName == pinnedFolderName
                        }
                // First scan (widget deep link): find the folder holding the entry section.
                else -> content.folders.firstOrNull { folder ->
                    folder.lists.any { it.uri == categoryUri }
                }
            }
            val unfiled = folder == null &&
                (pinnedIsUnfiled || (pinnedFolderUri == null && rootUri != null &&
                    content.unfiledLists.any { it.uri == categoryUri }))
            val pages = when {
                folder != null -> folder.lists
                unfiled -> content.unfiledLists
                else -> emptyList()
            }

            // Pin (or re-pin) the folder.
            when {
                folder != null -> {
                    pinnedFolderUri = folder.uri
                    pinnedFolderName = folder.displayName
                    pinnedIsUnfiled = false
                }
                unfiled && rootUri != null -> {
                    pinnedFolderUri = rootUri
                    pinnedFolderName = ""
                    pinnedIsUnfiled = true
                }
                else -> {} // nothing resolved yet; keep whatever was pinned
            }

            _uiState.value = ListPagerUiState(
                isLoading = false,
                pages = pages,
                parentPath = folder?.displayName
                    ?: if (unfiled) "" else _uiState.value.parentPath,
                parentFolderUri = folder?.uri
                    ?: if (unfiled) rootUri else _uiState.value.parentFolderUri,
                folderName = folder?.displayName
                    ?: if (unfiled) "" else _uiState.value.folderName,
            )
        }
    }

    /** One-shot jump marker consumed by the screen after scrolling to the created section. */
    fun jumpConsumed() {
        _uiState.value = _uiState.value.copy(pendingJumpUri = null)
    }

    /** Creates a section in this folder; the pager follows it once the scan includes it. */
    fun createSection(name: String, onCreated: (Uri?) -> Unit = {}) {
        viewModelScope.launch {
            val folderUri = _uiState.value.parentFolderUri ?: return@launch
            val created = fileRepository.createCategory(folderUri, name)
            if (created.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                onCreated(null)
                return@launch
            }
            refresh()
            _uiState.value = _uiState.value.copy(pendingJumpUri = created.getOrNull()?.toString())
            onCreated(created.getOrNull())
        }
    }

    /**
     * Called when the visible section renames itself from its own menu: just rescan — the
     * pinned folder is untouched by section renames, the pages rebuild from the fresh
     * listing, and the visible page re-keys onto the renamed document at the same position
     * (the folder pager's rename logic, verbatim).
     */
    fun onCategoryRenamed() {
        refresh()
    }

    /** Renames the folder; the scan re-resolves everything from the fresh listing. */
    fun renameFolder(newName: String) {
        viewModelScope.launch {
            val folderUri = _uiState.value.parentFolderUri ?: return@launch
            val currentName = _uiState.value.folderName.ifBlank { _uiState.value.parentPath }
            val result = fileRepository.renameFolder(folderUri, currentName, newName)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                refresh()
            }
        }
    }

    /** Applies a color-emoji to the folder's name (null strips the current one). */
    fun setFolderColor(emoji: String?) {
        val currentName = _uiState.value.folderName.ifBlank { _uiState.value.parentPath }
        pickedColorToName(currentName, emoji)?.let { renameFolder(it) }
    }

    /** Deletes the folder (and its sections); the UI backs out to Home on success. */
    fun deleteFolder() {
        viewModelScope.launch {
            val folderUri = _uiState.value.parentFolderUri ?: return@launch
            val result = fileRepository.deleteFolder(folderUri)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
            } else {
                _events.emit(Event.FolderDeleted)
            }
        }
    }
}

private class ListPagerViewModelFactory(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListPagerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ListPagerViewModel(fileRepository, categoryUri) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
