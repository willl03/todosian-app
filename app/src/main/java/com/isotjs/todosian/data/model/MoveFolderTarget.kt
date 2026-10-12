package com.isotjs.todosian.data.model

import android.net.Uri

/**
 * A destination folder for moving a whole list. [isUnfiled] marks the vault-root pseudo-folder
 * ("Unfiled lists"), whose [displayName] is resolved to a localized string by the UI.
 */
data class MoveFolderTarget(
    val displayName: String,
    val uri: Uri,
    val isUnfiled: Boolean = false,
)
