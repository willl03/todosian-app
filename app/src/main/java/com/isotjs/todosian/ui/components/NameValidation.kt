package com.isotjs.todosian.ui.components

import androidx.annotation.StringRes
import com.isotjs.todosian.R

/** Result of validating a new list/folder name in a rename sheet. */
data class NameValidation(
    /** The name to persist, or null when the input is invalid. */
    val normalizedName: String,
    @param:StringRes val errorMessageResId: Int? = null,
) {
    val isValid: Boolean get() = errorMessageResId == null
}

internal fun validationError(empty: Boolean, syncConflict: Boolean, invalidChars: Boolean): Int? = when {
    empty -> R.string.home_rename_folder_error_empty
    syncConflict -> R.string.home_rename_folder_error_sync_conflict
    invalidChars -> R.string.home_rename_folder_error_invalid_chars
    else -> null
}

private val INVALID_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

private fun hasInvalidFilenameChars(input: String): Boolean = input.any { it in INVALID_FILENAME_CHARS }

private fun hasSyncConflictMarker(input: String): Boolean = input.contains("sync-conflict", ignoreCase = true)

/**
 * Validates a **folder** name: trimmed, non-empty, no sync-conflict marker, no invalid filename
 * characters. Folders have no file extension handling.
 */
fun validateFolderName(input: String): NameValidation {
    val trimmed = input.trim()
    val error = validationError(
        empty = trimmed.isEmpty(),
        syncConflict = trimmed.isNotEmpty() && hasSyncConflictMarker(trimmed),
        invalidChars = trimmed.isNotEmpty() && hasInvalidFilenameChars(trimmed),
    ) ?: return NameValidation(normalizedName = trimmed)

    return NameValidation(normalizedName = "", errorMessageResId = error)
}

/**
 * Validates a **list** name like a Markdown document name: strips a `.md` suffix in any case
 * combination, requires a non-empty base, and appends `.md`.
 */
fun validateListFileName(input: String): NameValidation {
    val trimmed = input.trim()
    val base = trimmed
        .removeSuffix(".md")
        .removeSuffix(".MD")
        .removeSuffix(".Md")
        .removeSuffix(".mD")
        .trim()
    val error = validationError(
        empty = base.isEmpty(),
        syncConflict = base.isNotEmpty() && hasSyncConflictMarker(base),
        invalidChars = base.isNotEmpty() && hasInvalidFilenameChars(base),
    ) ?: return NameValidation(normalizedName = "$base.md")

    return NameValidation(normalizedName = "", errorMessageResId = error)
}
