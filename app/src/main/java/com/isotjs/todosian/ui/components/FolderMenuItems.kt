package com.isotjs.todosian.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.isotjs.todosian.R

/**
 * The folder overflow menu, shared by the folder view and the section pager's fixed folder bar.
 * Must be placed inside a [androidx.compose.material3.DropdownMenu]. [onDismiss] closes the
 * menu before the action runs; [onReorder], when non-null, adds the entry that opens the
 * folder view (where sections can be reordered).
 */
@Composable
fun FolderMenuItems(
    onAddSection: (() -> Unit)? = null,
    onRename: () -> Unit,
    onPickColor: () -> Unit,
    onDelete: () -> Unit,
    onReorder: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    onAddSection?.let { addSection ->
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.folder_action_add_section)) },
            onClick = {
                onDismiss()
                addSection()
            },
        )
    }
    onReorder?.let { reorder ->
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.folder_action_reorder)) },
            onClick = {
                onDismiss()
                reorder()
            },
        )
    }
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.home_folder_action_rename)) },
        onClick = {
            onDismiss()
            onRename()
        },
    )
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.home_folder_action_color)) },
        onClick = {
            onDismiss()
            onPickColor()
        },
    )
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.home_folder_action_delete)) },
        onClick = {
            onDismiss()
            onDelete()
        },
    )
}
