package com.isotjs.todosian.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isotjs.todosian.R
import com.isotjs.todosian.utils.leadingEmoji
import com.isotjs.todosian.utils.withLeadingEmoji

/** The color palette offered for folder names. Free-form emojis typed manually also work. */
val FOLDER_COLOR_EMOJIS = listOf("🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "🟤", "⚫", "⚪")

/**
 * Emoji color picker for a folder: a palette plus a "None" option. The current emoji
 * (the leading emoji of [folderName]) is highlighted. [onPick] receives the chosen emoji,
 * or null for "None" — the caller turns that into the folder's new name via a rename.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderColorPickerSheet(
    folderName: String,
    onPick: (emoji: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.folder_color_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            val current = remember(folderName) { leadingEmoji(folderName) }
            FOLDER_COLOR_EMOJIS.chunked(5).forEach { rowEmojis ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowEmojis.forEach { emoji ->
                        val selected = emoji == current
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    shape = CircleShape,
                                )
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                                .clickable { onPick(emoji) },
                        ) {
                            Text(text = emoji, fontSize = 26.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val noneSelected = current == null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clickable { onPick(null) }
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Block,
                    contentDescription = null,
                    tint = if (noneSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = stringResource(R.string.folder_color_none),
                    textAlign = TextAlign.Center,
                    style = if (noneSelected) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

/** Computes the folder's new name for a picked emoji; null (invalid) means "keep the old". */
fun pickedColorToName(currentName: String, emoji: String?): String? =
    withLeadingEmoji(currentName, emoji)
