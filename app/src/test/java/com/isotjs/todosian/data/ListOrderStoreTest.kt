package com.isotjs.todosian.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure logic of the manual order preference. The SAF file I/O lives in [ListOrderStore] and is
 * exercised on a device; everything here is deliberately framework-free.
 */
class ListOrderStoreTest {

    // region parse / serialize

    @Test
    fun `round trip preserves folders and lists`() {
        val order = StoredOrder(
            folders = listOf("Work", "Personal"),
            lists = mapOf(
                ListOrder.UNFILED_KEY to listOf("Inbox"),
                "Work" to listOf("Projects", "Meetings"),
            ),
        )

        assertEquals(order, ListOrder.parse(ListOrder.serialize(order)))
    }

    @Test
    fun `malformed json parses to null`() {
        assertNull(ListOrder.parse("{not json"))
        assertNull(ListOrder.parse(""))
        assertNull(ListOrder.parse("null"))
        assertNull(ListOrder.parse("[1,2,3]"))
    }

    @Test
    fun `missing or wrong version parses to null`() {
        assertNull(ListOrder.parse("""{"folders":[]}"""))
        assertNull(ListOrder.parse("""{"version":2,"folders":[]}"""))
        assertNull(ListOrder.parse("""{"version":null,"folders":[]}"""))
    }

    @Test
    fun `string version number is accepted leniently`() {
        val parsed = ListOrder.parse("""{"version":"1","folders":["Work"]}""")

        assertEquals(listOf("Work"), parsed?.folders)
    }

    @Test
    fun `non-string entries are skipped`() {
        val order = ListOrder.parse(
            """{"version":1,"folders":["Work",42,null,{"x":1}],"lists":{"":["a",3]}}""",
        )

        assertEquals(listOf("Work"), order?.folders)
        assertEquals(listOf("a"), order?.lists?.getValue(""))
    }

    @Test
    fun `unicode and special characters round trip`() {
        val order = StoredOrder(
            folders = listOf("Ünïcodé 🎉"),
            lists = mapOf("Ünïcodé 🎉" to listOf("with \"quotes\" and \\backslash\\")),
        )

        assertEquals(order, ListOrder.parse(ListOrder.serialize(order)))
    }

    @Test
    fun `empty sections round trip`() {
        val order = StoredOrder(folders = emptyList(), lists = emptyMap())

        assertEquals(order, ListOrder.parse(ListOrder.serialize(order)))
    }

    // endregion

    // region effective order

    private data class Item(val name: String)

    private val keyOf: (Item) -> String = { it.name }

    @Test
    fun `no stored keys fall back to case-insensitive A-Z`() {
        val items = listOf(Item("banana"), Item("Apple"), Item("cherry"))

        assertEquals(listOf("Apple", "banana", "cherry"), ListOrder.effective(items, keyOf, emptyList()).map(keyOf))
    }

    @Test
    fun `stored order wins for existing entries`() {
        val items = listOf(Item("a"), Item("b"), Item("c"))

        assertEquals(listOf("c", "a", "b"), ListOrder.effective(items, keyOf, listOf("c", "a", "b")).map(keyOf))
    }

    @Test
    fun `stale stored keys are skipped`() {
        val items = listOf(Item("a"), Item("b"))

        assertEquals(listOf("b", "a"), ListOrder.effective(items, keyOf, listOf("deleted", "b", "a")).map(keyOf))
    }

    @Test
    fun `unknown entries are appended case-insensitively`() {
        val items = listOf(Item("delta"), Item("alpha"), Item("Charlie"))

        assertEquals(
            listOf("alpha", "Charlie", "delta"),
            ListOrder.effective(items, keyOf, listOf("alpha")).map(keyOf),
        )
    }

    @Test
    fun `duplicate stored keys are deduplicated keeping first position`() {
        val items = listOf(Item("a"), Item("b"))

        assertEquals(listOf("b", "a"), ListOrder.effective(items, keyOf, listOf("b", "a", "b")).map(keyOf))
    }

    @Test
    fun `duplicate item keys share the stored position`() {
        // Two same-named folders are possible when files are created outside the app.
        val items = listOf(Item("Work"), Item("a"), Item("Work"))

        assertEquals(
            listOf("Work", "a", "Work"),
            ListOrder.effective(items, keyOf, listOf("Work")).map(keyOf),
        )
    }

    @Test
    fun `stored keys referencing missing entries keep their relative order`() {
        val items = listOf(Item("a"), Item("b"), Item("c"))

        assertEquals(
            listOf("c", "a", "b"),
            ListOrder.effective(items, keyOf, listOf("gone1", "c", "gone2", "a", "b")).map(keyOf),
        )
    }

    // endregion

    // region section replacement

    @Test
    fun `withFolderOrder replaces only the folders section`() {
        val order = StoredOrder(folders = listOf("old"), lists = mapOf("Work" to listOf("a")))

        val updated = order.withFolderOrder(listOf("new"))

        assertEquals(listOf("new"), updated.folders)
        assertEquals(mapOf("Work" to listOf("a")), updated.lists)
    }

    @Test
    fun `withListOrder replaces only the given parent section`() {
        val order = StoredOrder(
            folders = listOf("Work"),
            lists = mapOf("Work" to listOf("a"), ListOrder.UNFILED_KEY to listOf("b")),
        )

        val updated = order.withListOrder("Work", listOf("z", "y"))

        assertEquals(listOf("z", "y"), updated.lists.getValue("Work"))
        assertEquals(listOf("b"), updated.lists.getValue(ListOrder.UNFILED_KEY))
        assertEquals(listOf("Work"), updated.folders)
    }

    // endregion
}
