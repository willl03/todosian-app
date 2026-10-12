package com.isotjs.todosian.ui.components

/**
 * One row of a structured picker: a folder section header or a selectable entry.
 * Shared by the widget picker and the move/copy target pickers.
 */
sealed interface PickerRow<out T> {
    /** [name] is null for the unfiled pseudo-folder (resolved to a localized string in the UI). */
    data class FolderHeader<T>(val name: String?, val isUnfiled: Boolean = false) : PickerRow<T>
    data class ListRow<T>(val list: T) : PickerRow<T>
}

/**
 * Flat rows for a picker that preserves the vault structure: each folder ([name] to its lists)
 * becomes a section header followed by its lists, and the unfiled section comes last, only
 * when non-empty — mirroring the main app. Generic so it is unit-testable without Android
 * framework types (android.net.Uri is not mockable on the JVM).
 */
fun <T> buildPickerRows(
    folders: List<Pair<String, List<T>>>,
    unfiledLists: List<T>,
): List<PickerRow<T>> = buildList {
    folders.forEach { (name, lists) ->
        add(PickerRow.FolderHeader(name = name))
        lists.forEach { add(PickerRow.ListRow(it)) }
    }
    if (unfiledLists.isNotEmpty()) {
        add(PickerRow.FolderHeader(name = null, isUnfiled = true))
        unfiledLists.forEach { add(PickerRow.ListRow(it)) }
    }
}
