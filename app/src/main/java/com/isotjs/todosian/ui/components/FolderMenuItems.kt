package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R

/**
 * The folder overflow menu, shared by the folder view and the section pager's fixed folder bar.
 * Must be placed inside a [androidx.compose.material3.DropdownMenu]. [onDismiss] closes the
 * menu before the action runs; [onAddSection] and [onReorder], when non-null, add the
 * entries for creating a section and for opening the folder view (where sections can be
 * reordered).
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
            text = { Text(text = stringResource(R.string.folder_action_add_list)) },
            leadingIcon = { MenuIcon(Icons.Filled.Add) },
            onClick = {
                onDismiss()
                addSection()
            },
        )
    }
    onReorder?.let { reorder ->
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.folder_action_reorder)) },
            leadingIcon = { MenuIcon(Icons.Outlined.SwapVert) },
            onClick = {
                onDismiss()
                reorder()
            },
        )
    }
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.home_folder_action_rename)) },
        leadingIcon = { MenuIcon(Icons.Outlined.Edit) },
        onClick = {
            onDismiss()
            onRename()
        },
    )
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.home_folder_action_color)) },
        leadingIcon = { MenuIcon(Icons.Outlined.Palette) },
        onClick = {
            onDismiss()
            onPickColor()
        },
    )
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.home_folder_action_delete)) },
        leadingIcon = { MenuIcon(Icons.Outlined.Delete) },
        onClick = {
            onDismiss()
            onDelete()
        },
    )
}

/** A uniformly sized, muted menu icon. */
@Composable
private fun MenuIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
