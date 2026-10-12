package com.isotjs.todosian.ui.home

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.isotjs.todosian.BuildConfig
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.DailyFocusMode
import com.isotjs.todosian.data.model.Folder
import com.isotjs.todosian.ui.components.ChangelogBottomSheet
import com.isotjs.todosian.ui.components.DragReorderIndicatorSlot
import com.isotjs.todosian.ui.components.DragReorderState
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.dragReorderable
import com.isotjs.todosian.ui.components.todosianFabShape
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardOptions
import androidx.annotation.StringRes
import androidx.compose.ui.text.input.KeyboardCapitalization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    onOpenFolder: (Folder) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDailyFocus: () -> Unit,
    refreshSignal: Long,
    onRequireOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(fileRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showChangelogSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val folderUri = preferencesManager.getFolderUri()
        val lastSeenVersion = preferencesManager.getLastSeenVersionCode()
        if (folderUri != null && lastSeenVersion < BuildConfig.VERSION_CODE) {
            showChangelogSheet = true
            preferencesManager.saveLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Re-scan on every resume so tasks checked in the widget, edits made in Obsidian,
            // or sync changes that happened while the app was away are picked up. The banner
            // is the only consumer of todo counts on this screen; skip parsing without it.
            val showDailyFocus = appSettingsRepository.settings.first().showDailyFocus
            viewModel.refresh(showLoading = false, includeCounts = showDailyFocus)
        }
    }

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is HomeViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
                HomeViewModel.Event.RequireOnboarding -> onRequireOnboarding()
            }
        }
    }

    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.isotjs.todosian.data.settings.AppSettings(),
    )

    LaunchedEffect(refreshSignal) {
        if (refreshSignal > 0L) viewModel.refresh(includeCounts = settings.showDailyFocus)
    }

    var showNewFolderSheet by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Folder?>(null) }

    val scope = rememberCoroutineScope()
    val newFolderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val renameFolderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showNewFolderSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showNewFolderSheet = false
                newFolderName = ""
            },
            sheetState = newFolderSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_new_folder_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.home_new_folder_hint)) },
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
                                newFolderSheetState.hide()
                                showNewFolderSheet = false
                                newFolderName = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.createFolder(newFolderName)
                            scope.launch {
                                newFolderSheetState.hide()
                                showNewFolderSheet = false
                                newFolderName = ""
                            }
                        },
                        enabled = newFolderName.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        val validation = remember(renameValue) { validateFolderNameForRename(renameValue) }
        val errorText = validation.errorMessageResId?.let { stringResource(it) }
        ModalBottomSheet(
            onDismissRequest = {
                renameTarget = null
                renameValue = ""
            },
            sheetState = renameFolderSheetState,
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
                    value = renameValue,
                    onValueChange = { renameValue = it },
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
                                renameFolderSheetState.hide()
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
                            val normalized = validation.normalizedFolderName
                            if (target != null && normalized != null) {
                                viewModel.renameFolder(target.uri, normalized)
                            }
                            scope.launch {
                                renameFolderSheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                        enabled = validation.normalizedFolderName != null,
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
                        val target = deleteTarget
                        if (target != null) viewModel.deleteFolder(target.uri)
                        deleteTarget = null
                    },
                ) {
                    Text(text = stringResource(R.string.home_delete_folder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showChangelogSheet) {
        ChangelogBottomSheet(
            onDismissRequest = {
                showChangelogSheet = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewFolderSheet = true },
                shape = todosianFabShape(),
                // The cookie shape is concave; Skia's spot-shadow tessellation for concave
                // outlines is pathologically slow and freezes the RenderThread during
                // navigation animations. Keep depth via tonal colors instead of a shadow.
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_folder),
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
                    viewModel.refreshNow(showLoading = false, includeCounts = settings.showDailyFocus)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        // Folders A–Z (or manual order via the reorder feature); "Unfiled lists" is the last
        // row so its position matches the folder pager, where it is the rightmost page. It is
        // a virtual pseudo-folder: fixed at the bottom, not reorderable.
        val vaultRootUri = fileRepository.getFolderUri()
        val unfiledFolder = remember(uiState.unfiledLists, vaultRootUri) {
            if (uiState.unfiledLists.isNotEmpty() && vaultRootUri != null) {
                Folder(
                    displayName = "",
                    uri = vaultRootUri,
                    lists = uiState.unfiledLists,
                    isUnfiled = true,
                )
            } else {
                null
            }
        }
        val rows = remember(uiState.folders, unfiledFolder) {
            uiState.folders + listOfNotNull(unfiledFolder)
        }

        val listState = rememberLazyListState()
        val dragState = remember(listState) {
            DragReorderState(
                listState = listState,
                scope = scope,
                // Only real folders are drop targets; the fixed unfiled pseudo-row is not.
                onDrop = { from, to -> viewModel.dropFolder(from, to) },
                canTarget = { key -> key != vaultRootUri.toString() },
                reorderableCount = { uiState.folders.size },
            )
        }

        val contentState = remember(uiState.isLoading, rows.isEmpty()) {
            when {
                uiState.isLoading -> HomeContentState.LOADING
                rows.isEmpty() -> HomeContentState.EMPTY
                else -> HomeContentState.DATA
            }
        }

        AnimatedContent(
            targetState = contentState,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
            },
            label = "home-content",
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) { state ->
            when (state) {
                HomeContentState.LOADING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        TodosianLoadingIndicator()
                    }
                }

                HomeContentState.EMPTY -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = settings.showDailyFocus,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                        ) {
                            Column {
                                DailyFocusBanner(
                                    remaining = uiState.remainingFor(settings.dailyFocusMode),
                                    mode = settings.dailyFocusMode,
                                    onClick = onOpenDailyFocus,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        EmptyHomeState(
                            onCreateFolder = { showNewFolderSheet = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                HomeContentState.DATA -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = settings.showDailyFocus,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                        ) {
                            Column {
                                DailyFocusBanner(
                                    remaining = uiState.remainingFor(settings.dailyFocusMode),
                                    mode = settings.dailyFocusMode,
                                    onClick = onOpenDailyFocus,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = uiState.folders,
                                key = { _, folder -> folder.uri.toString() },
                            ) { folderIndex, folder ->
                                val rowKey = folder.uri.toString()
                                Column(modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    fadeOutSpec = null,
                                )) {
                                    DragReorderIndicatorSlot(dragState, rowKey)
                                    FolderRow(
                                        folder = folder,
                                        onClick = { onOpenFolder(folder) },
                                        onRename = {
                                            renameTarget = folder
                                            renameValue = folder.displayName
                                        },
                                        onDelete = { deleteTarget = folder },
                                        onMoveUp = folderIndex.takeIf { it > 0 }?.let { from ->
                                            { viewModel.dropFolder(from, from - 1) }
                                        },
                                        onMoveDown = folderIndex.takeIf { it < uiState.folders.lastIndex }?.let { from ->
                                            { viewModel.dropFolder(from, from + 1) }
                                        },
                                        modifier = Modifier.dragReorderable(dragState, rowKey, MaterialTheme.shapes.extraLarge),
                                    )
                                    DragReorderIndicatorSlot(dragState, rowKey, below = true)
                                }
                            }
                            if (unfiledFolder != null) {
                                item(key = unfiledFolder.uri.toString()) {
                                    val rowKey = unfiledFolder.uri.toString()
                                    Column(modifier = Modifier.animateItem(
                                        fadeInSpec = null,
                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        fadeOutSpec = null,
                                    )) {
                                        DragReorderIndicatorSlot(dragState, rowKey)
                                        FolderRow(
                                            folder = unfiledFolder,
                                            onClick = { onOpenFolder(unfiledFolder) },
                                            onRename = {},
                                            onDelete = {},
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
        }
    }
}

private enum class HomeContentState {
    LOADING,
    EMPTY,
    DATA,
}

private data class RenameFolderValidation(
    val normalizedFolderName: String?,
    @param:StringRes val errorMessageResId: Int?,
)

private fun validateFolderNameForRename(input: String): RenameFolderValidation {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return RenameFolderValidation(
            normalizedFolderName = null,
            errorMessageResId = R.string.home_rename_folder_error_empty,
        )
    }

    if (trimmed.contains("sync-conflict", ignoreCase = true)) {
        return RenameFolderValidation(
            normalizedFolderName = null,
            errorMessageResId = R.string.home_rename_folder_error_sync_conflict,
        )
    }

    val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    if (trimmed.any { it in invalidChars }) {
        return RenameFolderValidation(
            normalizedFolderName = null,
            errorMessageResId = R.string.home_rename_folder_error_invalid_chars,
        )
    }

    return RenameFolderValidation(
        normalizedFolderName = trimmed,
        errorMessageResId = null,
    )
}

@Composable
private fun DailyFocusBanner(
    remaining: Int,
    mode: DailyFocusMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_daily_focus_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = pluralStringResource(
                    when (mode) {
                        DailyFocusMode.TODAY -> R.plurals.home_daily_focus_subtitle_today
                        DailyFocusMode.OVERDUE -> R.plurals.home_daily_focus_subtitle_overdue
                        DailyFocusMode.TODAY_AND_OVERDUE -> R.plurals.home_daily_focus_subtitle_today_overdue
                    },
                    remaining,
                    remaining,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides the action; non-null moves the row by one position. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    var menuExpanded by remember(folder.uri) { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(
                imageVector = if (folder.isUnfiled) Icons.Outlined.Unarchive else Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = if (folder.isUnfiled) {
                        stringResource(R.string.home_unfiled_title)
                    } else {
                        folder.displayName
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val listNames = folder.lists.joinToString(separator = ", ") { it.displayName }
                Text(
                    text = if (listNames.isEmpty()) {
                        stringResource(R.string.home_folder_lists_empty)
                    } else {
                        listNames
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!folder.isUnfiled) {
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
                            text = { Text(text = stringResource(R.string.home_folder_action_rename)) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        if (onMoveUp != null) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.home_folder_action_move_up)) },
                                onClick = {
                                    menuExpanded = false
                                    onMoveUp()
                                },
                            )
                        }
                        if (onMoveDown != null) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.home_folder_action_move_down)) },
                                onClick = {
                                    menuExpanded = false
                                    onMoveDown()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.home_folder_action_delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHomeState(
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TodosianEmptyState(
        icon = Icons.Outlined.CreateNewFolder,
        title = stringResource(R.string.home_empty_title),
        subtitle = stringResource(R.string.home_empty_subtitle),
        actionLabel = stringResource(R.string.home_create_folder),
        onAction = onCreateFolder,
        modifier = modifier,
    )
}

private class HomeViewModelFactory(
    private val fileRepository: FileRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(fileRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
