package com.isotjs.todosian.ui.category

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.MoveFolderTarget
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.NewTodoFilePosition
import com.isotjs.todosian.data.settings.TodoGrouping
import com.isotjs.todosian.data.settings.TodoSort
import com.isotjs.todosian.ui.components.TasksMetaEditor
import com.isotjs.todosian.ui.components.TodoRow
import com.isotjs.todosian.ui.components.validateListFileName
import com.isotjs.todosian.ui.components.TodoSheetMode
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.TodosianSectionHeader
import com.isotjs.todosian.ui.components.todosianFabShape
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TodoSorter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardOptions
import java.time.LocalDate

/**
 * The full todo UI for a single section: a compact section bar, todo rows, add/edit/move
 * sheets and the FAB. Self-contained so it can be used as a page inside the folder's
 * [HorizontalPager-based] section pager (see [ListPagerScreen]); the fixed folder bar and the
 * back navigation live one level up, in the pager.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    categoryUri: Uri,
    modifier: Modifier = Modifier,
    autoOpenAddTodo: Boolean = false,
    /** Order-file key of the containing folder: the folder name, "" for unfiled. */
    parentPath: String = "",
    /** Document URI of the containing folder (vault root for unfiled); null when unknown. */
    parentFolderUri: Uri? = null,
    /** Section name from the pager scan; seeds the title before the file is read. */
    seededTitle: String = "",
) {
    val viewModel: CategoryViewModel = viewModel(
        key = "list_$categoryUri",
        factory = CategoryViewModelFactory(
            fileRepository = fileRepository,
            categoryUri = categoryUri,
            parentPath = parentPath,
            parentFolderUri = parentFolderUri,
            seededTitle = seededTitle,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The note may have changed while the app was away (widget toggle, Obsidian edit, sync);
    // re-read the file whenever the screen becomes visible again.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshFromDisk(showLoading = false, skipIfFresh = true)
        }
    }

    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.isotjs.todosian.data.settings.AppSettings(),
    )

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is CategoryViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val todoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val renameListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moveListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var sheetMode by remember { mutableStateOf<TodoSheetMode?>(null) }
    var sheetText by remember { mutableStateOf("") }
    var sheetMeta by remember { mutableStateOf(MarkdownParser.TasksMeta()) }
    var sheetParentTodo by remember { mutableStateOf<Todo?>(null) }
    var deleteTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var deleteTodoHasSubtasks by remember { mutableStateOf(false) }
    var moveTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var showCopyOption by remember { mutableStateOf(false) }

    var listMenuExpanded by remember { mutableStateOf(false) }
    // Frozen when the menu opens so the toggle item keeps its label/icon while the menu
    // collapses; the real setting updates on click and is re-read the next time it opens.
    var menuShowCompleted by remember { mutableStateOf(true) }
    var showRenameListSheet by remember { mutableStateOf(false) }
    var renameListValue by remember { mutableStateOf("") }
    var showDeleteListDialog by remember { mutableStateOf(false) }
    var showMoveListSheet by remember { mutableStateOf(false) }

    var autoOpenedAddTodo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoOpenAddTodo && !autoOpenedAddTodo) {
            autoOpenedAddTodo = true
            sheetMode = TodoSheetMode.Add
            sheetText = ""
            sheetMeta = if (settings.enableTasksPluginSupport) {
                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
            } else {
                MarkdownParser.TasksMeta()
            }
        }
    }

    if (sheetMode != null) {
        ModalBottomSheet(
            onDismissRequest = {
                sheetMode = null
                sheetText = ""
                sheetMeta = MarkdownParser.TasksMeta()
                sheetParentTodo = null
            },
            sheetState = todoSheetState,
        ) {
            val scrollState = rememberScrollState()
            val todoTextBringIntoView = remember { BringIntoViewRequester() }
            val recurrenceBringIntoView = remember { BringIntoViewRequester() }
            val titleRes = when (sheetMode) {
                TodoSheetMode.Add -> R.string.category_add_todo_title
                is TodoSheetMode.Edit -> R.string.category_edit_todo_title
                TodoSheetMode.AddSubtask -> R.string.category_add_subtask_title
                null -> R.string.category_add_todo_title
            }

            val hintRes = when (sheetMode) {
                TodoSheetMode.Add -> R.string.category_add_todo_hint
                is TodoSheetMode.Edit -> R.string.category_edit_todo_hint
                TodoSheetMode.AddSubtask -> R.string.category_add_subtask_hint
                null -> R.string.category_add_todo_hint
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp)
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = sheetText,
                    onValueChange = { sheetText = it },
                    singleLine = true,
                    label = { Text(text = stringResource(hintRes)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(todoTextBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch { todoTextBringIntoView.bringIntoView() }
                            }
                        },
                )

                if (settings.enableTasksPluginSupport) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TasksMetaEditor(
                        mode = sheetMode,
                        meta = sheetMeta,
                        onMetaChange = { sheetMeta = it },
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        modifier = Modifier
                            .bringIntoViewRequester(recurrenceBringIntoView)
                            .onFocusEvent { focusState ->
                                if (focusState.isFocused) {
                                    scope.launch { recurrenceBringIntoView.bringIntoView() }
                                }
                            },
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
                                todoSheetState.hide()
                                sheetMode = null
                                sheetText = ""
                                sheetMeta = MarkdownParser.TasksMeta()
                                sheetParentTodo = null
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            when (val mode = sheetMode) {
                                TodoSheetMode.Add -> viewModel.addTodo(
                                    text = sheetText,
                                    meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                    enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                    addAtStart = settings.newTodoFilePosition == NewTodoFilePosition.TOP,
                                )

                                is TodoSheetMode.Edit -> viewModel.editTodo(
                                    todo = mode.todo,
                                    newText = sheetText,
                                    meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                    enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                )

                                TodoSheetMode.AddSubtask -> {
                                    val parent = sheetParentTodo
                                    if (parent != null) {
                                        viewModel.addSubTodo(
                                            parent = parent,
                                            text = sheetText,
                                            meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                            enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                        )
                                    }
                                }

                                null -> Unit
                            }
                            scope.launch {
                                todoSheetState.hide()
                                sheetMode = null
                                sheetText = ""
                                sheetMeta = MarkdownParser.TasksMeta()
                                sheetParentTodo = null
                            }
                        },
                        enabled = sheetText.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (deleteTodoTarget != null) {
        val deleteBodyRes = if (deleteTodoHasSubtasks) {
            R.string.category_delete_todo_body_with_subtasks
        } else {
            R.string.category_delete_todo_body
        }
        AlertDialog(
            onDismissRequest = { deleteTodoTarget = null },
            title = { Text(text = stringResource(R.string.category_delete_todo_title)) },
            text = { Text(text = stringResource(deleteBodyRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTodoTarget
                        if (target != null) {
                            viewModel.deleteTodo(target)
                        }
                        deleteTodoTarget = null
                        deleteTodoHasSubtasks = false
                    },
                ) {
                    Text(text = stringResource(R.string.category_delete_todo_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTodoTarget = null
                    deleteTodoHasSubtasks = false
                }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (moveTodoTarget != null) {
        LaunchedEffect(moveTodoTarget) {
            viewModel.loadMoveTargets()
        }
        ModalBottomSheet(
            onDismissRequest = {
                moveTodoTarget = null
                showCopyOption = false
            },
            sheetState = moveSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.category_move_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.category_move_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val targets = uiState.moveTargets
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
                            text = stringResource(R.string.category_move_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        targets.forEach { target ->
                            ListItem(
                                headlineContent = { Text(text = target.title) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val todo = moveTodoTarget
                                    if (todo != null) {
                                        viewModel.moveTodo(todo, target.uri)
                                    }
                                    moveTodoTarget = null
                                    showCopyOption = false
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { showCopyOption = !showCopyOption }) {
                            Text(
                                text = if (showCopyOption) {
                                    stringResource(R.string.category_copy_hide)
                                } else {
                                    stringResource(R.string.category_copy_show)
                                },
                            )
                        }

                        if (showCopyOption) {
                            Spacer(modifier = Modifier.height(8.dp))
                            targets.forEach { target ->
                                ListItem(
                                    headlineContent = { Text(text = target.title) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = null,
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val todo = moveTodoTarget
                                        if (todo != null) {
                                            viewModel.copyTodo(todo, target.uri)
                                        }
                                        moveTodoTarget = null
                                        showCopyOption = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameListSheet) {
        val validation = remember(renameListValue) { validateListFileName(renameListValue) }
        val errorText = validation.errorMessageResId?.let { stringResource(it) }
        ModalBottomSheet(
            onDismissRequest = {
                showRenameListSheet = false
                renameListValue = ""
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
                    value = renameListValue,
                    onValueChange = { renameListValue = it },
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
                                showRenameListSheet = false
                                renameListValue = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.renameCategory(renameListValue)
                            scope.launch {
                                renameListSheetState.hide()
                                showRenameListSheet = false
                                renameListValue = ""
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

    if (showDeleteListDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteListDialog = false },
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
                        viewModel.deleteCategory()
                        showDeleteListDialog = false
                    },
                ) {
                    Text(text = stringResource(R.string.folder_delete_list_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteListDialog = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showMoveListSheet) {
        LaunchedEffect(Unit) {
            viewModel.loadMoveFolderTargets()
        }
        ModalBottomSheet(
            onDismissRequest = { showMoveListSheet = false },
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
                                    viewModel.moveCategory(target)
                                    showMoveListSheet = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            // Compact section bar: moves with the pages; the fixed folder bar (name, folder
            // menu) lives one level up in the section pager. No status-bar inset here — the
            // pager's top bar already accounts for it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = TodosianDimens.ScreenHorizontalPadding,
                        vertical = 6.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiState.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = {
                        menuShowCompleted = settings.showCompleted
                        listMenuExpanded = true
                    }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_overflow_menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = listMenuExpanded,
                        onDismissRequest = { listMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(
                                        if (menuShowCompleted) {
                                            R.string.list_menu_hide_completed
                                        } else {
                                            R.string.list_menu_show_completed
                                        },
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (menuShowCompleted) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                listMenuExpanded = false
                                appSettingsRepository.setShowCompleted(!menuShowCompleted)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.folder_list_action_rename)) },
                            onClick = {
                                listMenuExpanded = false
                                renameListValue = uiState.title
                                showRenameListSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.folder_list_action_move)) },
                            onClick = {
                                listMenuExpanded = false
                                showMoveListSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.folder_list_action_delete)) },
                            onClick = {
                                listMenuExpanded = false
                                showDeleteListDialog = true
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    sheetMode = TodoSheetMode.Add
                    sheetText = ""
                    sheetMeta = if (settings.enableTasksPluginSupport) {
                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                    } else {
                        MarkdownParser.TasksMeta()
                    }
                },
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
                    contentDescription = stringResource(R.string.cd_add_todo),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                TodosianLoadingIndicator()
            }
            return@Scaffold
        }

        // Hidden completed tasks do not count towards the "is anything visible" check.
        val anyTodos = uiState.activeTodos.isNotEmpty() ||
            (settings.showCompleted && uiState.completedTodos.isNotEmpty())
        if (!anyTodos) {
            TodosianEmptyState(
                icon = Icons.Outlined.Checklist,
                title = stringResource(R.string.category_empty_title),
                subtitle = stringResource(R.string.category_empty_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }

        val sortedActiveTodos = remember(uiState.activeTodos, settings.todoSort) {
            TodoSorter.sort(uiState.activeTodos, settings.todoSort)
        }
        val sortedCompletedTodos = remember(uiState.completedTodos, settings.todoSort, settings.showCompleted) {
            if (settings.showCompleted) TodoSorter.sort(uiState.completedTodos, settings.todoSort) else emptyList()
        }
        val sortedAllTodos = remember(
            uiState.activeTodos,
            uiState.completedTodos,
            settings.todoSort,
            settings.showCompleted,
        ) {
            TodoSorter.sort(
                uiState.activeTodos + uiState.completedTodos.filter { settings.showCompleted || !it.isDone },
                settings.todoSort,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) {
            if (settings.todoGrouping == TodoGrouping.FILE_ORDER) {
                items(
                    items = sortedAllTodos,
                    key = { it.id },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        showSubtaskButton = settings.enableSubtasks,
                        onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(todo)
                            sheetText = todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = todo.dueDate,
                                startDate = todo.startDate,
                                scheduledDate = todo.scheduledDate,
                                completionDate = todo.completionDate,
                                createdDate = todo.createdDate,
                                priority = todo.priority,
                                recurrence = todo.recurrence,
                            )
                        },
                        onAddSubtask = {
                            sheetMode = TodoSheetMode.AddSubtask
                            sheetParentTodo = todo
                            sheetText = ""
                            sheetMeta = if (settings.enableTasksPluginSupport) {
                                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                            } else {
                                MarkdownParser.TasksMeta()
                            }
                        },
                        onRequestDelete = {
                            deleteTodoTarget = todo
                            deleteTodoHasSubtasks = MarkdownParser.hasSubtasks(uiState.lines, todo.lineIndex)
                        },
                        onRequestMove = { moveTodoTarget = todo },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item { Spacer(modifier = Modifier.height(96.dp)) }
                return@LazyColumn
            }

            if (sortedActiveTodos.isNotEmpty()) {
                item { TodosianSectionHeader(text = stringResource(R.string.category_active)) }
                items(
                    items = sortedActiveTodos,
                    key = { it.id },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        showSubtaskButton = settings.enableSubtasks,
                        onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(todo)
                            sheetText = todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = todo.dueDate,
                                startDate = todo.startDate,
                                scheduledDate = todo.scheduledDate,
                                completionDate = todo.completionDate,
                                createdDate = todo.createdDate,
                                priority = todo.priority,
                                recurrence = todo.recurrence,
                            )
                        },
                        onAddSubtask = {
                            sheetMode = TodoSheetMode.AddSubtask
                            sheetParentTodo = todo
                            sheetText = ""
                            sheetMeta = if (settings.enableTasksPluginSupport) {
                                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                            } else {
                                MarkdownParser.TasksMeta()
                            }
                        },
                        onRequestDelete = {
                            deleteTodoTarget = todo
                            deleteTodoHasSubtasks = MarkdownParser.hasSubtasks(uiState.lines, todo.lineIndex)
                        },
                        onRequestMove = { moveTodoTarget = todo },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (sortedCompletedTodos.isNotEmpty()) {
                item { TodosianSectionHeader(text = stringResource(R.string.category_completed)) }
                items(
                    items = sortedCompletedTodos,
                    key = { it.id },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        showSubtaskButton = settings.enableSubtasks,
                        onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(todo)
                            sheetText = todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = todo.dueDate,
                                startDate = todo.startDate,
                                scheduledDate = todo.scheduledDate,
                                completionDate = todo.completionDate,
                                createdDate = todo.createdDate,
                                priority = todo.priority,
                                recurrence = todo.recurrence,
                            )
                        },
                        onAddSubtask = {
                            sheetMode = TodoSheetMode.AddSubtask
                            sheetParentTodo = todo
                            sheetText = ""
                            sheetMeta = if (settings.enableTasksPluginSupport) {
                                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                            } else {
                                MarkdownParser.TasksMeta()
                            }
                        },
                        onRequestDelete = {
                            deleteTodoTarget = todo
                            deleteTodoHasSubtasks = MarkdownParser.hasSubtasks(uiState.lines, todo.lineIndex)
                        },
                        onRequestMove = { moveTodoTarget = todo },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

class CategoryViewModelFactory(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
    private val parentPath: String = "",
    private val parentFolderUri: Uri? = null,
    private val seededTitle: String = "",
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CategoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CategoryViewModel(fileRepository, categoryUri, parentPath, parentFolderUri, seededTitle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
