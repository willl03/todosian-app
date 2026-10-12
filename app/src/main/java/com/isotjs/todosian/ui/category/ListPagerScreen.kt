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
    /** The section the user is currently on; re-anchored by name across folder renames. */
    val currentListUri: String? = null,
)

/**
 * Swipeable pager over a folder's sections. Resolves the folder containing [categoryUri] itself,
 * so widget intents can route straight into a section without knowing its folder. A fixed folder
 * bar (name, folder menu) stays on top while the pages — each a [CategoryScreen] with its own
 * compact section bar — swipe underneath.
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

    // Follow the view model's anchor: lands on the requested section the first time pages
    // arrive, and re-anchors after a folder rename (when every page URI changes).
    var anchoredUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pages, uiState.currentListUri) {
        val target = uiState.currentListUri ?: return@LaunchedEffect
        if (target != anchoredUri) {
            val index = pages.indexOfFirst { it.uri.toString() == target }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                anchoredUri = target
            }
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
                onCategoryRenamed = { newName ->
                    pagerViewModel.onCategoryRenamed(newName)
                },
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
     * The section the user is on. Matched by URI first; after a folder rename every child URI
     * changes, so [anchorName] keeps the position stable across the re-scan.
     */
    private var anchorUri: Uri = categoryUri
    private var anchorName: String? = null

    init {
        observeVaultChanges()
        // Instant first frame: the sending screen knows this folder's lists; the scan below
        // reconciles — the seed is a one-shot navigation argument.
        val seed = NavSeed.consumeListPager(categoryUri)
        if (seed != null) {
            anchorName = seed.lists
                .firstOrNull { it.uri == categoryUri }
                ?.displayName
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

    fun refresh() {
        viewModelScope.launch {
            val content = fileRepository.getVaultContent(includeCounts = false).getOrNull()
            // Find the folder holding the anchored section: by URI, then by display name
            // (a folder rename changes every child URI; the name is what stays stable).
            var containingFolder = content?.folders?.firstOrNull { folder ->
                folder.lists.any { it.uri == anchorUri }
            }
            if (containingFolder == null && content != null && anchorName != null) {
                containingFolder = content.folders.firstOrNull { folder ->
                    folder.lists.any { it.displayName == anchorName }
                }
            }
            val unfiled = containingFolder == null && content != null &&
                content.unfiledLists.any { it.uri == anchorUri || it.displayName == anchorName }
            val pages = containingFolder?.lists
                ?: if (unfiled) content!!.unfiledLists else emptyList()

            // Re-anchor: same section by URI, then by name, then the first page.
            val current = pages.firstOrNull { it.uri == anchorUri }
                ?: pages.firstOrNull { it.displayName == anchorName }
                ?: pages.firstOrNull()
            if (current != null) {
                anchorUri = current.uri
                anchorName = current.displayName
            }

            _uiState.value = ListPagerUiState(
                isLoading = false,
                pages = pages,
                parentPath = containingFolder?.displayName ?: "",
                parentFolderUri = containingFolder?.uri
                    ?: if (pages.isNotEmpty()) fileRepository.getFolderUri() else null,
                folderName = containingFolder?.displayName ?: "",
                currentListUri = current?.uri?.toString(),
            )
        }
    }

    /** Creates a section in this folder and re-anchors the pager onto it. */
    fun createSection(name: String, onCreated: (Uri?) -> Unit = {}) {
        viewModelScope.launch {
            val folderUri = _uiState.value.parentFolderUri ?: return@launch
            val created = fileRepository.createCategory(folderUri, name)
            if (created.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                onCreated(null)
                return@launch
            }
            // Anchor on the new section so the pager follows it after the refresh.
            anchorUri = created.getOrNull() ?: anchorUri
            anchorName = name
            refresh()
            onCreated(created.getOrNull())
        }
    }

    /**
     * Called when the visible section renames itself from its own menu: re-anchor onto the
     * section by its new name (the rename invalidated its URI) and rescan immediately, so the
     * pager re-keys the page onto the renamed document without waiting for the file observer.
     */
    fun onCategoryRenamed(newName: String) {
        anchorName = newName
        refresh()
    }

    /** Renames the folder; the scan re-anchors the visible section by name afterwards. */
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
