package com.isotjs.todosian.ui.components

import android.app.DatePickerDialog
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.utils.MarkdownParser
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TodoRow(
    todo: Todo,
    enableTasksPluginSupport: Boolean,
    useEmojisInUi: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAddSubtask: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestMove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    allowMove: Boolean = onRequestMove != null,
    showSubtaskButton: Boolean = true,
) {
    val indentPadding = (todo.indentLevel * 12).coerceAtMost(48).dp
    val rowShape = MaterialTheme.shapes.large
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .padding(start = indentPadding)
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = rowShape,
            )
            .clip(rowShape)
            // Tap opens the editor; long-press opens the Move/Delete actions (the swipe
            // gestures were removed — they fought with the pager's horizontal swipes).
            .combinedClickable(
                onClick = onEdit,
                onLongClick = { menuExpanded = true },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
            Checkbox(
                checked = todo.isDone,
                onCheckedChange = { onToggle() },
            )
            Column(
                modifier = Modifier.weight(1f),
            ) {
                val targetColor = if (todo.isDone) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                val textColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(300),
                    label = "todo-text-color",
                )
                // The priority is shown in the footer's chip (a colored dot + P1–P4);
                // no dot next to the name anymore.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = todo.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (todo.isDone) TextDecoration.LineThrough else TextDecoration.None,
                        color = textColor,
                    )
                }

                // The task's note: shown as a simple text row above the metadata line,
                // only when the task carries one. Edited in the add/edit sheet.
                if (!todo.note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = noteLinkifiedText(todo.note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (enableTasksPluginSupport) {
                    val chips = buildTasksMetaChips(todo = todo, useEmojisInUi = useEmojisInUi)
                    if (chips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            chips.forEach { chip ->
                                androidx.compose.material3.AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    leadingIcon = chip.icon?.let { icon ->
                                        {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                // The priority chip's dot carries the
                                                // priority color; other icons stay muted.
                                                tint = chip.color ?: LocalContentColor.current,
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = chip.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (showSubtaskButton && todo.indentLevel < 2) {
                IconButton(onClick = onAddSubtask) {
                    Icon(
                        imageVector = Icons.Outlined.SubdirectoryArrowRight,
                        contentDescription = stringResource(R.string.cd_add_subtask),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        Box {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                // Move is only offered for top-level entries; subtasks can't be moved.
                if (allowMove && onRequestMove != null && todo.indentLevel == 0) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.todo_row_action_move)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.DriveFileMove,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onRequestMove?.invoke()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.todo_row_action_delete)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRequestDelete()
                    },
                )
            }
        }
    }
}

@Composable
fun TasksMetaEditor(
    mode: TodoSheetMode?,
    meta: MarkdownParser.TasksMeta,
    onMetaChange: (MarkdownParser.TasksMeta) -> Unit,
    useEmojisInUi: Boolean,
    modifier: Modifier = Modifier,
    /** Invoked when the note field's keyboard action (✓) fires; lets the sheet save. */
    onNoteImeAction: () -> Unit = {},
) {
    val isAdd = mode is TodoSheetMode.Add
    // The completion date is set automatically when a task is checked in the list; the field
    // is only surfaced when editing an already-completed task, to view or correct the date.
    val isDoneTodo = (mode as? TodoSheetMode.Edit)?.todo?.isDone == true

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.category_tasks_metadata_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        DateMetaRow(
            label = if (useEmojisInUi) {
                stringResource(R.string.category_tasks_created)
            } else {
                stringResource(R.string.category_tasks_created_label)
            },
            icon = if (useEmojisInUi) null else Icons.Outlined.AddCircle,
            value = meta.createdDate,
            allowClear = !isAdd,
            onPick = { picked -> onMetaChange(meta.copy(createdDate = picked)) },
            onClear = { onMetaChange(meta.copy(createdDate = null)) },
        )

        DateMetaRow(
            label = if (useEmojisInUi) stringResource(R.string.category_tasks_due) else stringResource(R.string.category_tasks_due_label),
            icon = if (useEmojisInUi) null else Icons.Outlined.Event,
            value = meta.dueDate,
            allowClear = true,
            onPick = { picked -> onMetaChange(meta.copy(dueDate = picked)) },
            onClear = { onMetaChange(meta.copy(dueDate = null)) },
        )

        if (isDoneTodo) {
            DateMetaRow(
                label = if (useEmojisInUi) stringResource(R.string.category_tasks_done) else stringResource(R.string.category_tasks_done_label),
                icon = if (useEmojisInUi) null else Icons.Outlined.CheckCircle,
                value = meta.completionDate,
                allowClear = true,
                onPick = { picked -> onMetaChange(meta.copy(completionDate = picked)) },
                onClear = { onMetaChange(meta.copy(completionDate = null)) },
            )
        }

        // Start, scheduled, and completion dates are intentionally not editable here: the
        // completion date is set automatically when a task is checked in the list, and the
        // other two tokens remain supported in the files (edited in Obsidian) without being
        // surfaced in this sheet.

        OutlinedTextField(
            value = meta.note.orEmpty(),
            onValueChange = { value ->
                onMetaChange(meta.copy(note = value.ifBlank { null }))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onNoteImeAction() }),
            label = {
                Text(
                    text = if (useEmojisInUi) {
                        stringResource(R.string.category_tasks_note)
                    } else {
                        stringResource(R.string.category_tasks_note_label)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        PriorityMetaRow(
            value = meta.priority,
            onChange = { onMetaChange(meta.copy(priority = it)) },
        )
    }
}

sealed interface TodoSheetMode {
    data object Add : TodoSheetMode
    data class Edit(val todo: Todo) : TodoSheetMode
    data object AddSubtask : TodoSheetMode
}

@Composable
private fun DateMetaRow(
    label: String,
    icon: ImageVector?,
    value: String?,
    allowClear: Boolean,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        TextButton(
            onClick = {
                val initial = runCatching { value?.let(LocalDate::parse) }.getOrNull() ?: LocalDate.now()
                showDatePicker(
                    context = context,
                    initial = initial,
                    onPicked = onPick,
                )
            },
        ) {
            Text(text = value ?: stringResource(R.string.action_set))
        }

        if (allowClear && value != null) {
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.action_clear))
            }
        }
    }
}

@Composable
private fun PriorityMetaRow(
    value: TasksPriority?,
    onChange: (TasksPriority?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Todoist-style priorities, mapped onto the Obsidian Tasks tokens the files use:
    // P1 → 🔺 (highest), P2 → ⏫ (high), P3 → 🔼 (medium), P4 → no token (the default).
    // Legacy 🔽/⏬ tasks stay supported by the parser but are not offered here.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.category_tasks_priority),
            style = MaterialTheme.typography.bodyMedium,
        )

        PriorityChip(
            selected = value == TasksPriority.HIGHEST,
            label = stringResource(R.string.category_tasks_priority_p1),
            onClick = { onChange(TasksPriority.HIGHEST) },
            icon = Icons.Filled.Circle,
            iconTint = colorResource(R.color.priority_highest),
        )
        PriorityChip(
            selected = value == TasksPriority.HIGH,
            label = stringResource(R.string.category_tasks_priority_p2),
            onClick = { onChange(TasksPriority.HIGH) },
            icon = Icons.Filled.Circle,
            iconTint = colorResource(R.color.priority_high),
        )
        PriorityChip(
            selected = value == TasksPriority.MEDIUM,
            label = stringResource(R.string.category_tasks_priority_p3),
            onClick = { onChange(TasksPriority.MEDIUM) },
            icon = Icons.Filled.Circle,
            iconTint = colorResource(R.color.priority_medium),
        )
        PriorityChip(
            selected = value == null || value == TasksPriority.NONE,
            label = stringResource(R.string.category_tasks_priority_p4),
            onClick = { onChange(null) },
            icon = Icons.Filled.Circle,
            iconTint = colorResource(R.color.priority_low),
        )
    }
}

@Composable
private fun PriorityChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: ImageVector?,
    iconTint: Color? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = icon?.let { image ->
            {
                Icon(
                    imageVector = image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint ?: LocalContentColor.current,
                )
            }
        },
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

internal fun showDatePicker(
    context: android.content.Context,
    initial: LocalDate,
    onPicked: (String) -> Unit,
) {
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val picked = LocalDate.of(year, month + 1, dayOfMonth)
            onPicked(picked.toString())
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

internal data class TasksMetaChipUi(
    val label: String,
    val icon: ImageVector?,
    val color: Color? = null,
)

/**
 * The footer note with URLs turned into tappable links (opened in the browser). Links are
 * typed as plain text in the note field; only the footer display linkifies them.
 */
@Composable
private fun noteLinkifiedText(note: String): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        val urlRegex = Regex("""(?:https?://|www\.)\S+""")
        var index = 0
        for (match in urlRegex.findAll(note)) {
            if (match.range.first > index) append(note.substring(index, match.range.first))
            val shown = match.value
            // Don't include trailing punctuation in the link target.
            val trimmed = shown.trimEnd('.', ',', ';', ':', '!', '?', ')')
            val target = if (trimmed.startsWith("www.")) "https://$trimmed" else trimmed
            withLink(
                LinkAnnotation.Clickable(
                    tag = target,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                    ),
                ) {
                    uriHandler.openUri(target)
                }
            ) {
                append(shown)
            }
            index = match.range.last + 1
        }
        if (index < note.length) append(note.substring(index))
    }
}

@Composable
internal fun priorityColorFor(priority: TasksPriority?): Color? {
    return when (priority) {
        TasksPriority.LOWEST -> colorResource(R.color.priority_lowest)
        TasksPriority.LOW -> colorResource(R.color.priority_low)
        TasksPriority.MEDIUM -> colorResource(R.color.priority_medium)
        TasksPriority.HIGH -> colorResource(R.color.priority_high)
        TasksPriority.HIGHEST -> colorResource(R.color.priority_highest)
        TasksPriority.NONE, null -> null
    }
}

@Composable
internal fun buildTasksMetaChips(
    todo: Todo,
    useEmojisInUi: Boolean,
): List<TasksMetaChipUi> {
    val chips = ArrayList<TasksMetaChipUi>(8)

    val prio = todo.priority
    if (prio != null && prio != TasksPriority.NONE) {
        val label = when (prio) {
            TasksPriority.LOWEST -> if (useEmojisInUi) stringResource(R.string.category_tasks_priority_lowest) else stringResource(R.string.category_tasks_priority_lowest_label)
            TasksPriority.LOW -> if (useEmojisInUi) stringResource(R.string.category_tasks_priority_low) else stringResource(R.string.category_tasks_priority_low_label)
            TasksPriority.MEDIUM -> stringResource(R.string.category_tasks_priority_p3)
            TasksPriority.HIGH -> stringResource(R.string.category_tasks_priority_p2)
            TasksPriority.HIGHEST -> stringResource(R.string.category_tasks_priority_p1)
            TasksPriority.NONE -> ""
        }
        chips.add(
            TasksMetaChipUi(
                label = label,
                icon = Icons.Filled.Circle,
                color = priorityColorFor(prio),
            ),
        )
    }

    todo.dueDate?.let { value ->
        val label = if (useEmojisInUi) {
            stringResource(R.string.category_tasks_chip_due, value)
        } else {
            stringResource(R.string.category_tasks_chip_due_label, value)
        }
        chips.add(TasksMetaChipUi(label = label, icon = if (useEmojisInUi) null else Icons.Outlined.Event))
    }
    todo.scheduledDate?.let { value ->
        val label = if (useEmojisInUi) {
            stringResource(R.string.category_tasks_chip_scheduled, value)
        } else {
            stringResource(R.string.category_tasks_chip_scheduled_label, value)
        }
        chips.add(TasksMetaChipUi(label = label, icon = if (useEmojisInUi) null else Icons.Outlined.Schedule))
    }
    todo.startDate?.let { value ->
        val label = if (useEmojisInUi) {
            stringResource(R.string.category_tasks_chip_start, value)
        } else {
            stringResource(R.string.category_tasks_chip_start_label, value)
        }
        chips.add(TasksMetaChipUi(label = label, icon = if (useEmojisInUi) null else Icons.Outlined.FlightTakeoff))
    }
    todo.completionDate?.let { value ->
        val label = if (useEmojisInUi) {
            stringResource(R.string.category_tasks_chip_done, value)
        } else {
            stringResource(R.string.category_tasks_chip_done_label, value)
        }
        chips.add(TasksMetaChipUi(label = label, icon = if (useEmojisInUi) null else Icons.Outlined.CheckCircle))
    }

    // Recurrence is deliberately not shown (nor editable in the sheet): the 🔁 token stays
    // supported in the files for Obsidian Tasks compatibility, managed in Obsidian itself.

    return chips.filter { it.label.isNotBlank() }
}
