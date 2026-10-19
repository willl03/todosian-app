package com.isotjs.todosian.ui.widget

import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.TodoSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [sortTaskItemsPerList], the widget snapshot's per-list application of the app's
 * todo sort setting.
 */
class WidgetTaskSortTest {

    private fun todo(
        text: String,
        lineIndex: Int,
        priority: TasksPriority? = null,
        dueDate: String? = null,
        indentLevel: Int = 0,
    ) = Todo(
        id = "",
        text = text,
        isDone = false,
        lineIndex = lineIndex,
        indentLevel = indentLevel,
        dueDate = dueDate,
        priority = priority,
    )

    private fun item(
        todo: Todo,
        categoryUri: String = "content://a",
        listName: String = "List A",
    ) = WidgetTaskItem(
        categoryUri = categoryUri,
        listName = listName,
        todo = todo,
    )

    private fun texts(items: List<WidgetTaskItem>) = items.map { it.todo.text }

    @Test
    fun `file order is a no-op`() {
        val items = listOf(
            item(todo("b", lineIndex = 2)),
            item(todo("a", lineIndex = 5)),
        )

        assertEquals(items, sortTaskItemsPerList(items, TodoSort.FILE_ORDER))
    }

    @Test
    fun `priority sort reorders tasks within each list`() {
        val items = listOf(
            item(todo("none", lineIndex = 1)),
            item(todo("high", lineIndex = 2, priority = TasksPriority.HIGH)),
        )

        assertEquals(
            listOf("high", "none"),
            texts(sortTaskItemsPerList(items, TodoSort.PRIORITY_HIGH_TO_LOW)),
        )
    }

    @Test
    fun `list blocks stay intact across lists`() {
        val items = listOf(
            item(todo("a none", lineIndex = 1), categoryUri = "content://a", listName = "A"),
            item(todo("a high", lineIndex = 2, priority = TasksPriority.HIGH), categoryUri = "content://a", listName = "A"),
            item(todo("b low", lineIndex = 1, priority = TasksPriority.LOW), categoryUri = "content://b", listName = "B"),
        )

        // List A's tasks are sorted within the list, but list B never jumps ahead of list A
        // even though its task would sort between A's under a global ordering.
        assertEquals(
            listOf("a high", "a none", "b low"),
            texts(sortTaskItemsPerList(items, TodoSort.PRIORITY_HIGH_TO_LOW)),
        )
    }

    @Test
    fun `subtasks follow their parent after sorting`() {
        val items = listOf(
            item(todo("parent none", lineIndex = 1)),
            item(todo("subtask", lineIndex = 2, indentLevel = 1)),
            item(todo("parent high", lineIndex = 3, priority = TasksPriority.HIGH)),
        )

        assertEquals(
            listOf("parent high", "parent none", "subtask"),
            texts(sortTaskItemsPerList(items, TodoSort.PRIORITY_HIGH_TO_LOW)),
        )
    }

    @Test
    fun `due date sort orders dated tasks first`() {
        val items = listOf(
            item(todo("undated", lineIndex = 1)),
            item(todo("due later", lineIndex = 2, dueDate = "2026-10-20")),
            item(todo("due sooner", lineIndex = 3, dueDate = "2026-10-17")),
        )

        assertEquals(
            listOf("due sooner", "due later", "undated"),
            texts(sortTaskItemsPerList(items, TodoSort.DUE_DATE_EARLIEST_FIRST)),
        )
    }

    @Test
    fun `sorting before the completed split keeps the active section ordered`() {
        val items = listOf(
            item(todo("active none", lineIndex = 1)),
            item(todo("active high", lineIndex = 2, priority = TasksPriority.HIGH)),
            item(todo("done high", lineIndex = 3, priority = TasksPriority.HIGH).copy(isDone = true)),
        )

        val sorted = sortTaskItemsPerList(items, TodoSort.PRIORITY_HIGH_TO_LOW)
        val visible = filterCompletedItems(sorted, showCompleted = false)

        assertEquals(listOf("active high", "active none"), texts(visible))
    }
}
