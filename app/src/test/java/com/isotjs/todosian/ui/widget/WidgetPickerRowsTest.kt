package com.isotjs.todosian.ui.widget

import com.isotjs.todosian.ui.components.PickerRow
import com.isotjs.todosian.ui.components.buildPickerRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget picker's structure logic. Uses String payloads because android.net.Uri is not
 * mockable on the JVM — the payload type is opaque to the builder anyway.
 */
class WidgetPickerRowsTest {

    private fun describe(rows: List<PickerRow<String>>): List<String> = rows.map { row ->
        when (row) {
            is PickerRow.FolderHeader -> row.name ?: "unfiled"
            is PickerRow.ListRow -> row.list
        }
    }

    @Test
    fun `folders are headers with their lists nested, unfiled last`() {
        val rows = buildPickerRows<String>(
            folders = listOf(
                "Alpha" to listOf("Inbox", "Projects"),
                "Beta" to listOf("Ideas"),
            ),
            unfiledLists = listOf("Quick notes"),
        )

        assertEquals(
            listOf(
                "Alpha",
                "Inbox",
                "Projects",
                "Beta",
                "Ideas",
                "unfiled",
                "Quick notes",
            ),
            describe(rows),
        )
    }

    @Test
    fun `unfiled header is absent when the vault root has no lists`() {
        val rows = buildPickerRows<String>(
            folders = listOf("Work" to listOf<String>("Inbox")),
            unfiledLists = emptyList(),
        )

        assertEquals(listOf("Work", "Inbox"), describe(rows))
    }

    @Test
    fun `empty vault builds no rows`() {
        assertEquals(
            emptyList<PickerRow<String>>(),
            buildPickerRows<String>(folders = emptyList(), unfiledLists = emptyList()),
        )
    }

    @Test
    fun `folders without lists still show their header`() {
        val rows = buildPickerRows<String>(folders = listOf("Empty" to emptyList<String>()), unfiledLists = emptyList())

        assertEquals(listOf("Empty"), describe(rows))
    }

    @Test
    fun `unfiled-only vault shows just the unfiled section`() {
        val rows = buildPickerRows<String>(folders = emptyList(), unfiledLists = listOf("Inbox"))

        assertEquals(listOf("unfiled", "Inbox"), describe(rows))
    }
}
