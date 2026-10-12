package com.isotjs.todosian.ui.addtask

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.model.VaultContent
import com.isotjs.todosian.ui.NavSeed
import com.isotjs.todosian.ui.components.PickerRow
import com.isotjs.todosian.ui.components.buildPickerRows
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    fileRepository: FileRepository,
    onSelectList: (Uri) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    // When set (widget bound to a folder), the picker shows only that folder's lists.
    val scopedFolderUri = remember { NavSeed.consumeAddTaskFolder() }
    var scopedFolderName by remember { mutableStateOf<String?>(null) }
    var lists by remember { mutableStateOf<List<Category>>(emptyList()) }
    var pickerRows by remember { mutableStateOf<List<PickerRow<Category>>>(emptyList()) }

    LaunchedEffect(fileRepository) {
        val content = fileRepository.getVaultContent(includeCounts = false)
            .getOrDefault(VaultContent(folders = emptyList(), unfiledLists = emptyList()))
        if (scopedFolderUri != null) {
            val folder = content.folders.firstOrNull { it.uri == scopedFolderUri }
            scopedFolderName = folder?.displayName
            lists = folder?.lists.orEmpty()
        } else {
            lists = content.allLists()
            pickerRows = buildPickerRows(
                folders = content.folders.map { Triple(it.uri.toString(), it.displayName, it.lists) },
                unfiledLists = content.unfiledLists,
            )
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.add_task_picker_title)) },
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
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    TodosianLoadingIndicator()
                }
            }

            lists.isEmpty() -> {
                TodosianEmptyState(
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.add_task_picker_empty_title),
                    subtitle = stringResource(R.string.add_task_picker_empty_subtitle),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.add_task_picker_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    if (scopedFolderUri != null) {
                        // Folder-bound widget: only that folder's lists, no structure needed.
                        items(
                            items = lists,
                            key = { it.uri.toString() },
                        ) { list ->
                            ListRow(list, onSelectList)
                        }
                    } else {
                        // Unbound widget: the full vault structure, folders as headers.
                        pickerRows.forEach { row ->
                            when (row) {
                                is PickerRow.FolderHeader -> item(key = "header_${row.name}") {
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                text = row.name
                                                    ?: stringResource(R.string.home_unfiled_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Filled.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                    )
                                }
                                is PickerRow.ListRow -> item(key = row.list.uri.toString()) {
                                    ListRow(row.list, onSelectList)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(
    list: Category,
    onSelectList: (Uri) -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = list.displayName) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Checklist,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable {
            onSelectList(list.uri)
        },
    )
}
