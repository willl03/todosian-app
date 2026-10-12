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
    fun `trailing garbage from a non-truncating write parses to null`() {
        // Corruption seen on a device: a shorter write left a stale tail of the previous JSON
        // behind. The file must fail open instead of half-parsing.
        val corrupted = """{"version":1,"folders":[],"lists":{"Alpha":["a","b"]}}}""" +
            "\"]}}]}}lpha:[\"old1\",\"old2\"],\"Other\":[\"old3\"]}}ld3\"}}"

        assertNull(ListOrder.parse(corrupted))
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

    @Test
    fun `withRenamedFolder keeps position and moves the lists section key`() {
        val order = StoredOrder(
            folders = listOf("Work", "Personal"),
            lists = mapOf(
                "Work" to listOf("Projects", "Meetings"),
                ListOrder.UNFILED_KEY to listOf("Inbox"),
            ),
        )

        val updated = order.withRenamedFolder("Work", "Deep Work")

        // Folder keeps its position.
        assertEquals(listOf("Deep Work", "Personal"), updated.folders)
        // The lists section travels with the new key.
        assertEquals(listOf("Projects", "Meetings"), updated.lists.getValue("Deep Work"))
        assertNull(updated.lists["Work"])
        assertEquals(listOf("Inbox"), updated.lists.getValue(ListOrder.UNFILED_KEY))
    }

    @Test
    fun `withRenamedFolder for untracked folder is a no-op on content`() {
        val order = StoredOrder(folders = listOf("A"), lists = mapOf("A" to listOf("x")))

        val updated = order.withRenamedFolder("Z", "Y")

        assertEquals(listOf("A"), updated.folders)
        assertEquals(mapOf("A" to listOf("x")), updated.lists)
    }

    @Test
    fun `withRenamedList renames in place only within its section`() {
        val order = StoredOrder(
            folders = listOf("Work", "Personal"),
            lists = mapOf(
                "Work" to listOf("a", "b", "c"),
                "Personal" to listOf("a"),
            ),
        )

        val updated = order.withRenamedList("Work", "b", "renamed")

        assertEquals(listOf("a", "renamed", "c"), updated.lists.getValue("Work"))
        assertEquals(listOf("a"), updated.lists.getValue("Personal"))
    }

    @Test
    fun `withRenamedList for unordered section is a no-op`() {
        val order = StoredOrder(folders = listOf("Work"))

        val updated = order.withRenamedList("Work", "old", "new")

        assertEquals(listOf("Work"), updated.folders)
        assertEquals(emptyMap<String, List<String>>(), updated.lists)
    }

    // endregion

    // region pruneSections

    @Test
    fun `prune removes stale folder entries and lists sections`() {
        val order = StoredOrder(
            folders = listOf("Work", "Gone"),
            lists = mapOf(
                "Work" to listOf("a"),
                "Gone" to listOf("b"),
                ListOrder.UNFILED_KEY to listOf("c"),
            ),
        )

        val pruned = order.pruneSections(setOf("Work"))

        assertEquals(listOf("Work"), pruned.folders)
        assertEquals(
            mapOf("Work" to listOf("a"), ListOrder.UNFILED_KEY to listOf("c")),
            pruned.lists,
        )
    }

    @Test
    fun `prune keeps unfiled section always`() {
        val order = StoredOrder(lists = mapOf(ListOrder.UNFILED_KEY to listOf("a"), "X" to listOf("b")))

        val pruned = order.pruneSections(emptySet())

        assertEquals(mapOf(ListOrder.UNFILED_KEY to listOf("a")), pruned.lists)
    }

    @Test
    fun `prune with null valid parents is a no-op`() {
        val order = StoredOrder(folders = listOf("X"), lists = mapOf("X" to listOf("a")))

        assertEquals(order, order.pruneSections(null))
    }

    // endregion
}
