package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.TodoSort
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoSorterTest {

    private fun todo(
        text: String,
        lineIndex: Int,
        dueDate: String? = null,
        priority: TasksPriority? = null,
        indentLevel: Int = 0,
    ) = Todo(
        id = "id-$lineIndex",
        text = text,
        isDone = false,
        lineIndex = lineIndex,
        indentLevel = indentLevel,
        dueDate = dueDate,
        priority = priority,
    )

    private fun names(todos: List<Todo>) = todos.map { it.text }

    @Test
    fun `due date + priority — the full ordering`() {
        val todos = listOf(
            todo("low priority", lineIndex = 3, priority = TasksPriority.LOW),
            todo("due 2026-10-12 low", lineIndex = 2, dueDate = "2026-10-12", priority = TasksPriority.LOW),
            todo("no priority created after", lineIndex = 6),
            todo("due 2026-10-12 high", lineIndex = 1, dueDate = "2026-10-12", priority = TasksPriority.HIGH),
            todo("highest priority", lineIndex = 4, priority = TasksPriority.HIGHEST),
            todo("due 2026-10-11 no priority", lineIndex = 0, dueDate = "2026-10-11"),
            todo("no priority created before", lineIndex = 5),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.DUE_DATE_THEN_PRIORITY)

        assertEquals(
            listOf(
                "due 2026-10-11 no priority",
                "due 2026-10-12 high",
                "due 2026-10-12 low",
                "highest priority",
                "low priority",
                "no priority created before",
                "no priority created after",
            ),
            names(sorted),
        )
    }

    @Test
    fun `same due date is tie-broken by priority then file order`() {
        val todos = listOf(
            todo("b", lineIndex = 1, dueDate = "2026-01-01"),
            todo("a", lineIndex = 0, dueDate = "2026-01-01"),
        )

        assertEquals(listOf("a", "b"), names(TodoSorter.sort(todos, TodoSort.DUE_DATE_THEN_PRIORITY)))
    }

    @Test
    fun `subtasks follow their parent regardless of their own dates`() {
        val todos = listOf(
            todo("parent", lineIndex = 0, dueDate = "2026-03-01"),
            todo("parent", lineIndex = 2, dueDate = "2026-01-01", priority = TasksPriority.HIGH),
            todo("child of late parent, due earlier", lineIndex = 3, dueDate = "2026-01-01", indentLevel = 1),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.DUE_DATE_THEN_PRIORITY)

        // The January parent sorts first; its child (also January) must stay attached to it,
        // not float to the top on its own date.
        assertEquals(
            listOf("parent", "child of late parent, due earlier", "parent"),
            names(sorted),
        )
        // parent (Jan, lineIndex 2) first, then its child (lineIndex 3), then the March parent.
        assertEquals(2, sorted[0].lineIndex)
        assertEquals(3, sorted[1].lineIndex)
        assertEquals(0, sorted[2].lineIndex)
    }

    @Test
    fun `undated children of undated parents keep file order`() {
        val todos = listOf(
            todo("first", lineIndex = 0),
            todo("subtask", lineIndex = 1, indentLevel = 1),
            todo("second", lineIndex = 2),
        )

        assertEquals(
            listOf("first", "subtask", "second"),
            names(TodoSorter.sort(todos, TodoSort.DUE_DATE_THEN_PRIORITY)),
        )
    }

    @Test
    fun `priority sort keeps subtask groups attached (existing behavior preserved)`() {
        val todos = listOf(
            todo("low parent", lineIndex = 0, priority = TasksPriority.LOW),
            todo("high parent", lineIndex = 2, priority = TasksPriority.HIGH),
            todo("child of low", lineIndex = 1, indentLevel = 1),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.PRIORITY_HIGH_TO_LOW)

        assertEquals(listOf("high parent", "low parent", "child of low"), names(sorted))
    }

    @Test
    fun `file order is untouched`() {
        // Given out of line order; FILE_ORDER restores the file position order.
        val todos = listOf(
            todo("b", lineIndex = 1),
            todo("a", lineIndex = 0),
        )

        assertEquals(listOf("a", "b"), names(TodoSorter.sort(todos, TodoSort.FILE_ORDER)))
    }
}
