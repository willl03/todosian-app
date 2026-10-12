package com.isotjs.todosian.data.model

import android.net.Uri

/**
 * A single Markdown list document (a `.md` file) stored either in the vault root (the "Unfiled
 * lists" pseudo-folder) or inside a [Folder]. The user-facing term is "list".
 */
data class TodoList(
    val displayName: String,
    val uri: Uri,
    val todoCount: Int,
    val doneCount: Int,
    val dueTodayCount: Int,
    val overdueCount: Int,
)
