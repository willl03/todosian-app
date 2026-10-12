package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.model.priorityRank
import com.isotjs.todosian.data.settings.TodoSort

/** A top-level todo with its deeper-indented subtasks; groups are kept intact while sorting. */
data class TodoTaskGroup(val parent: Todo, val children: List<Todo>)

/**
 * Pure todo ordering, shared by the list screen (and testable without Android framework
 * classes). All modes fall back to [Todo.lineIndex] (file order) as the last tie-breaker.
 */
object TodoSorter {

    fun sort(todos: List<Todo>, sort: TodoSort): List<Todo> = when (sort) {
        TodoSort.FILE_ORDER -> todos.sortedBy { it.lineIndex }

        TodoSort.PRIORITY_HIGH_TO_LOW -> sortGroupsKeepingSubtasks(
            todos,
            compareByDescending<TodoTaskGroup> { it.parent.priority.priorityRank() }
                .thenBy { it.parent.lineIndex },
        )

        // Dated entries on top (earliest first), then by priority (highest first), then file
        // order — covering undated entries (sorted by priority among themselves) and entries
        // without any explicit order (file order).
        TodoSort.DUE_DATE_THEN_PRIORITY -> sortGroupsKeepingSubtasks(
            todos,
            compareBy<TodoTaskGroup> { it.parent.dueDate == null }
                .thenBy { it.parent.dueDate ?: "" }
                .thenByDescending { it.parent.priority.priorityRank() }
                .thenBy { it.parent.lineIndex },
        )

        TodoSort.CREATED_DATE_NEWEST_FIRST -> todos.sortedWith(
            compareByDescending<Todo> { it.createdDate != null }
                .thenByDescending { it.createdDate ?: "" }
                .thenBy { it.lineIndex },
        )

        TodoSort.DUE_DATE_EARLIEST_FIRST -> todos.sortedWith(
            compareBy<Todo> { it.dueDate == null }
                .thenBy { it.dueDate ?: "" }
                .thenBy { it.lineIndex },
        )
    }

    /**
     * Sorts parent+subtask groups with [groupOrder], children always following their parent —
     * a subtask never detaches from its parent by its own dates/priority.
     */
    private fun sortGroupsKeepingSubtasks(
        todos: List<Todo>,
        groupOrder: Comparator<TodoTaskGroup>,
    ): List<Todo> {
        if (todos.isEmpty()) return todos

        val ordered = todos.sortedBy { it.lineIndex }
        val groups = ArrayList<TodoTaskGroup>()

        var currentParent: Todo? = null
        var currentChildren = ArrayList<Todo>()

        fun flushGroup() {
            val parent = currentParent
            if (parent != null) {
                groups.add(TodoTaskGroup(parent = parent, children = currentChildren.toList()))
            }
        }

        for (todo in ordered) {
            if (todo.indentLevel == 0 || currentParent == null) {
                flushGroup()
                currentParent = todo
                currentChildren = ArrayList()
            } else {
                currentChildren.add(todo)
            }
        }

        flushGroup()

        val result = ArrayList<Todo>(ordered.size)
        for (group in groups.sortedWith(groupOrder)) {
            result.add(group.parent)
            result.addAll(group.children)
        }

        return result
    }
}
