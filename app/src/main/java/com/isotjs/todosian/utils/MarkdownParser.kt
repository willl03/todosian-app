package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import java.time.LocalDate

data class TaskMarkers(
    val created: String = "➕",
    val start: String = "🛫",
    val scheduled: String = "⏳",
    val due: String = "📅",
    val done: String = "✅",
    val recurrence: String = "🔁",
) {
    companion object {
        val Default = TaskMarkers()
    }
}

object MarkdownParser {
    private val todoRegex = Regex("""^([ \t]*)- \[(x| )\] (.*)$""")

    private val prioritySuffixRegex = Regex("""\s(🔺|⏫|🔼|🔽|⏬️?|⏬)\s*$""")

    // Notes are a fork-only token: they always use the 📝 marker and are not customizable.
    private val noteSuffixRegex = Regex("""\s📝\s(.+)$""")

    private fun dateSuffixRegex(marker: String) =
        Regex("""\s${Regex.escape(marker)}\s*(\d{4}-\d{2}-\d{2})\s*$""")

    private fun recurrenceSuffixRegex(marker: String) =
        Regex("""\s${Regex.escape(marker)}\s*(.+?)\s*$""")

    private fun completionAnywhereRegex(marker: String) =
        Regex("""\s${Regex.escape(marker)}\s*\d{4}-\d{2}-\d{2}(?=\s|$)""")

    private fun isTodoLine(line: String): Boolean = todoRegex.matches(line)

    fun parse(lines: List<String>, markers: TaskMarkers = TaskMarkers.Default): List<Todo> {
        return lines.mapIndexedNotNull { index, line ->
            val match = todoRegex.matchEntire(line) ?: return@mapIndexedNotNull null
            val indentPrefix = match.groupValues[1]
            val isDone = match.groupValues[2] == "x"
            val remainder = match.groupValues[3]

            val parsed = parseRemainder(remainder, markers)
            Todo(
                // Stable across re-parses of unchanged content: the todo lists key their rows
                // by [Todo.id], so a random id would re-key (and re-animate) every row on each
                // re-read. lineIndex keeps ids unique; the text hash keeps toggles and metadata
                // edits smooth while genuine text edits re-key that one row.
                id = "$index-${parsed.mainText.hashCode()}",
                text = parsed.mainText,
                isDone = isDone,
                lineIndex = index,
                indentLevel = indentLevel(indentPrefix),
                dueDate = parsed.meta.dueDate,
                startDate = parsed.meta.startDate,
                scheduledDate = parsed.meta.scheduledDate,
                completionDate = parsed.meta.completionDate,
                createdDate = parsed.meta.createdDate,
                priority = parsed.meta.priority,
                recurrence = parsed.meta.recurrence,
                note = parsed.meta.note,
            )
        }
    }

    fun toggleLine(
        lines: List<String>,
        lineIndex: Int,
        enableTasksPlugin: Boolean,
        today: LocalDate = LocalDate.now(),
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String> {
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines

        val indentPrefix = match.groupValues[1]
        val isDone = match.groupValues[2] == "x"
        val remainder = match.groupValues[3]
        val newMark = if (isDone) " " else "x"

        val todayStr = today.toString()

        val newRemainder = if (!enableTasksPlugin) {
            remainder
        } else {
            val withoutDoneDate = removeCompletionDate(remainder, markers)
            if (!isDone) {
                withoutDoneDate + " ${markers.done} $todayStr"
            } else {
                withoutDoneDate
            }
        }

        val newLine = "$indentPrefix- [$newMark] ${newRemainder.trimEnd()}"
        val updatedLines = lines.toMutableList().apply {
            this[lineIndex] = newLine
        }

        if (!isDone && enableTasksPlugin) {
            val parsed = parseRemainder(remainder, markers)
            val rule = parsed.meta.recurrence
            if (!rule.isNullOrBlank()) {
                val nextLine = buildRecurredTaskLine(
                    indentPrefix = indentPrefix,
                    taskText = parsed.mainText,
                    originalMeta = parsed.meta,
                    rule = rule,
                    today = today,
                    markers = markers,
                )
                if (nextLine != null) {
                    return updatedLines.toMutableList().apply {
                        add(lineIndex, nextLine)
                    }
                }
            }
        }

        return updatedLines
    }

    fun tryToggleLine(
        lines: List<String>,
        lineIndex: Int,
        enableTasksPlugin: Boolean,
        today: LocalDate = LocalDate.now(),
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String>? {
        if (lineIndex !in lines.indices) return null
        val line = lines[lineIndex]
        if (!isTodoLine(line)) return null
        return toggleLine(lines, lineIndex, enableTasksPlugin, today, markers)
    }

    fun addTodo(
        lines: List<String>,
        text: String,
        meta: TasksMeta? = null,
        enableTasksPlugin: Boolean = false,
        addAtStart: Boolean = false,
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return lines

        val newLine = buildTodoLine(
            text = cleaned,
            meta = meta,
            enableTasksPlugin = enableTasksPlugin,
            indentPrefix = "",
            markers = markers,
        )

        if (lines.isEmpty()) return listOf(newLine)
        return lines.toMutableList().apply {
            if (addAtStart) add(frontmatterEndIndex(this), newLine) else add(newLine)
        }
    }

    private fun frontmatterEndIndex(lines: List<String>): Int {
        if (lines.firstOrNull() != "---") return 0
        for (i in 1 until lines.size) {
            if (lines[i] == "---") return i + 1
        }
        return 0
    }

    fun addSubTodo(
        lines: List<String>,
        parentLineIndex: Int,
        text: String,
        meta: TasksMeta? = null,
        enableTasksPlugin: Boolean = false,
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String>? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        if (parentLineIndex !in lines.indices) return null

        val parentLine = lines[parentLineIndex]
        val match = todoRegex.matchEntire(parentLine) ?: return null
        val parentIndentPrefix = match.groupValues[1]
        val parentIndentLevel = indentLevel(parentIndentPrefix)

        val indentUnit = inferIndentUnit(parentIndentPrefix)
        val newIndentPrefix = parentIndentPrefix + indentUnit

        val newLine = buildTodoLine(
            text = cleaned,
            meta = meta,
            enableTasksPlugin = enableTasksPlugin,
            indentPrefix = newIndentPrefix,
            markers = markers,
        )

        var insertIndex = parentLineIndex + 1
        while (insertIndex < lines.size) {
            val nextLine = lines[insertIndex]
            val nextMatch = todoRegex.matchEntire(nextLine) ?: break
            val nextIndent = nextMatch.groupValues[1]
            if (indentLevel(nextIndent) > parentIndentLevel) {
                insertIndex++
            } else {
                break
            }
        }

        return lines.toMutableList().apply { add(insertIndex, newLine) }
    }

    fun tryDeleteTodoWithSubtasks(lines: List<String>, lineIndex: Int): List<String>? {
        if (lineIndex !in lines.indices) return null
        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return null
        val parentIndent = indentLevel(match.groupValues[1])

        var endIndex = lineIndex + 1
        while (endIndex < lines.size) {
            val nextLine = lines[endIndex]
            val nextMatch = todoRegex.matchEntire(nextLine) ?: break
            val nextIndent = indentLevel(nextMatch.groupValues[1])
            if (nextIndent > parentIndent) {
                endIndex++
            } else {
                break
            }
        }

        return lines.toMutableList().apply {
            subList(lineIndex, endIndex).clear()
        }
    }

    fun hasSubtasks(lines: List<String>, lineIndex: Int): Boolean {
        if (lineIndex !in lines.indices) return false
        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return false
        val parentIndent = indentLevel(match.groupValues[1])

        val nextIndex = lineIndex + 1
        if (nextIndex !in lines.indices) return false
        val nextLine = lines[nextIndex]
        val nextMatch = todoRegex.matchEntire(nextLine) ?: return false
        val nextIndent = indentLevel(nextMatch.groupValues[1])
        return nextIndent > parentIndent
    }

    fun editTodoText(
        lines: List<String>,
        lineIndex: Int,
        newText: String,
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String> {
        val cleaned = newText.trim()
        if (cleaned.isEmpty()) return lines
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines
        val indentPrefix = match.groupValues[1]
        val mark = match.groupValues[2]
        val remainder = match.groupValues[3]
        val parsed = parseRemainder(remainder, markers)

        val newRemainder = buildString {
            append(cleaned)
            if (parsed.suffixRaw.isNotEmpty()) append(parsed.suffixRaw)
        }

        val newLine = "$indentPrefix- [$mark] ${newRemainder.trimEnd()}"
        return lines.toMutableList().apply { this[lineIndex] = newLine }
    }

    fun tryEditTodoText(
        lines: List<String>,
        lineIndex: Int,
        newText: String,
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String>? {
        val cleaned = newText.trim()
        if (cleaned.isEmpty()) return null
        if (lineIndex !in lines.indices) return null

        val line = lines[lineIndex]
        if (!isTodoLine(line)) return null
        return editTodoText(lines, lineIndex, cleaned, markers)
    }

    fun tryCopyTodoLine(
        sourceLines: List<String>,
        lineIndex: Int,
        targetLines: List<String>,
    ): Pair<List<String>, List<String>>? {
        if (lineIndex !in sourceLines.indices) return null
        val line = sourceLines[lineIndex]
        if (!isTodoLine(line)) return null

        val newTargetLines = targetLines.toMutableList().apply { add(line) }
        return sourceLines to newTargetLines
    }

    fun tryMoveTodoLine(
        sourceLines: List<String>,
        lineIndex: Int,
        targetLines: List<String>,
    ): Pair<List<String>, List<String>>? {
        if (lineIndex !in sourceLines.indices) return null
        val line = sourceLines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return null
        val parentIndent = indentLevel(match.groupValues[1])

        var endIndex = lineIndex + 1
        while (endIndex < sourceLines.size) {
            val nextLine = sourceLines[endIndex]
            val nextMatch = todoRegex.matchEntire(nextLine) ?: break
            val nextIndent = indentLevel(nextMatch.groupValues[1])
            if (nextIndent > parentIndent) {
                endIndex++
            } else {
                break
            }
        }

        val block = sourceLines.subList(lineIndex, endIndex)
        val newSourceLines = sourceLines.toMutableList().apply {
            subList(lineIndex, endIndex).clear()
        }
        val newTargetLines = targetLines.toMutableList().apply { addAll(block) }
        return newSourceLines to newTargetLines
    }

    fun editTodo(
        lines: List<String>,
        lineIndex: Int,
        newText: String,
        meta: TasksMeta?,
        enableTasksPlugin: Boolean,
        markers: TaskMarkers = TaskMarkers.Default,
    ): List<String> {
        val cleaned = newText.trim()
        if (cleaned.isEmpty()) return lines
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines
        val indentPrefix = match.groupValues[1]
        val mark = match.groupValues[2]

        val resolvedMeta = if (enableTasksPlugin) meta else null
        val newLine = buildString {
            append(indentPrefix)
            append("- [")
            append(mark)
            append("] ")
            append(cleaned)
            val suffix = resolvedMeta?.toSuffixString(markers).orEmpty()
            if (suffix.isNotEmpty()) append(suffix)
        }

        return lines.toMutableList().apply { this[lineIndex] = newLine }
    }

    private fun todayString(today: LocalDate = LocalDate.now()): String = today.toString()

    private fun buildRecurredTaskLine(
        indentPrefix: String,
        taskText: String,
        originalMeta: TasksMeta,
        rule: String,
        today: LocalDate,
        markers: TaskMarkers,
    ): String? {
        val next = RecurrenceEngine.nextOccurrence(
            ruleText = rule,
            dueDate = originalMeta.dueDate,
            scheduledDate = originalMeta.scheduledDate,
            startDate = originalMeta.startDate,
            today = today,
        ) ?: return null

        val newMeta = TasksMeta(
            dueDate = next.dueDate?.toString(),
            scheduledDate = next.scheduledDate?.toString(),
            startDate = next.startDate?.toString(),
            completionDate = null,
            createdDate = if (originalMeta.createdDate != null) today.toString() else null,
            priority = originalMeta.priority,
            recurrence = originalMeta.recurrence,
        )

        return buildString {
            append(indentPrefix)
            append("- [ ] ")
            append(taskText)
            val suffix = newMeta.toSuffixString(markers)
            if (suffix.isNotEmpty()) append(suffix)
        }
    }

    private fun buildTodoLine(
        text: String,
        meta: TasksMeta?,
        enableTasksPlugin: Boolean,
        indentPrefix: String,
        markers: TaskMarkers,
    ): String {
        val resolvedMeta = if (enableTasksPlugin) {
            (meta ?: TasksMeta()).let { current ->
                if (current.createdDate == null) current.copy(createdDate = todayString()) else current
            }
        } else {
            null
        }

        return buildString {
            append(indentPrefix)
            append("- [ ] ")
            append(text)
            val suffix = resolvedMeta?.toSuffixString(markers).orEmpty()
            if (suffix.isNotEmpty()) append(suffix)
        }
    }

    private fun indentLevel(prefix: String): Int {
        if (prefix.isEmpty()) return 0
        var width = 0
        for (char in prefix) {
            width += if (char == '\t') 4 else 1
        }
        return (width / 2).coerceAtLeast(0)
    }

    private fun inferIndentUnit(prefix: String): String {
        return if (prefix.contains('\t')) "\t" else "  "
    }

    private fun removeCompletionDate(remainder: String, markers: TaskMarkers): String {
        val removed = remainder.replace(completionAnywhereRegex(markers.done), "")
        return removed.replace(Regex("""\s{2,}"""), " ").trimEnd()
    }

    private data class ParsedRemainder(
        val mainText: String,
        val suffixRaw: String,
        val meta: TasksMeta,
    )

    data class TasksMeta(
        val dueDate: String? = null,
        val startDate: String? = null,
        val scheduledDate: String? = null,
        val completionDate: String? = null,
        val createdDate: String? = null,
        val priority: TasksPriority? = null,
        val recurrence: String? = null,
        val note: String? = null,
    ) {
        fun toSuffixString(markers: TaskMarkers = TaskMarkers.Default): String {
            val parts = ArrayList<String>(8)
            if (createdDate != null) parts.add("${markers.created} $createdDate")
            if (startDate != null) parts.add("${markers.start} $startDate")
            if (scheduledDate != null) parts.add("${markers.scheduled} $scheduledDate")
            if (dueDate != null) parts.add("${markers.due} $dueDate")
            if (completionDate != null) parts.add("${markers.done} $completionDate")
            if (!recurrence.isNullOrBlank()) parts.add("${markers.recurrence} ${recurrence.trim()}")
            val prio = priority
            if (prio != null && prio != TasksPriority.NONE) {
                parts.add(priorityToEmoji(prio))
            }
            // The note is written last: its suffix regex captures to end-of-line, so no
            // other token may follow it.
            if (!note.isNullOrBlank()) parts.add("📝 ${note.trim()}")

            return if (parts.isEmpty()) {
                ""
            } else {
                " " + parts.joinToString(separator = " ")
            }
        }
    }

    private fun parseRemainder(remainder: String, markers: TaskMarkers): ParsedRemainder {
        var working = remainder.trimEnd()

        val completionRegex = dateSuffixRegex(markers.done)
        val dueRegex = dateSuffixRegex(markers.due)
        val startRegex = dateSuffixRegex(markers.start)
        val scheduledRegex = dateSuffixRegex(markers.scheduled)
        val createdRegex = dateSuffixRegex(markers.created)
        val recurrenceRegex = recurrenceSuffixRegex(markers.recurrence)

        val suffixParts = mutableListOf<String>()

        var dueDate: String? = null
        var startDate: String? = null
        var scheduledDate: String? = null
        var completionDate: String? = null
        var createdDate: String? = null
        var priority: TasksPriority? = null
        var recurrence: String? = null
        var note: String? = null

        while (true) {
            val completion = completionRegex.find(working)
            if (completion != null) {
                completionDate = completion.groupValues[1]
                suffixParts.add(0, working.substring(completion.range.first))
                working = working.substring(0, completion.range.first).trimEnd()
                continue
            }

            val due = dueRegex.find(working)
            if (due != null) {
                dueDate = due.groupValues[1]
                suffixParts.add(0, working.substring(due.range.first))
                working = working.substring(0, due.range.first).trimEnd()
                continue
            }

            val start = startRegex.find(working)
            if (start != null) {
                startDate = start.groupValues[1]
                suffixParts.add(0, working.substring(start.range.first))
                working = working.substring(0, start.range.first).trimEnd()
                continue
            }

            val scheduled = scheduledRegex.find(working)
            if (scheduled != null) {
                scheduledDate = scheduled.groupValues[1]
                suffixParts.add(0, working.substring(scheduled.range.first))
                working = working.substring(0, scheduled.range.first).trimEnd()
                continue
            }

            val created = createdRegex.find(working)
            if (created != null) {
                createdDate = created.groupValues[1]
                suffixParts.add(0, working.substring(created.range.first))
                working = working.substring(0, created.range.first).trimEnd()
                continue
            }

            val prio = prioritySuffixRegex.find(working)
            if (prio != null) {
                priority = emojiToPriority(prio.groupValues[1])
                suffixParts.add(0, working.substring(prio.range.first))
                working = working.substring(0, prio.range.first).trimEnd()
                continue
            }

            val recur = recurrenceRegex.find(working)
            if (recur != null) {
                recurrence = recur.groupValues[1].trim()
                suffixParts.add(0, working.substring(recur.range.first))
                working = working.substring(0, recur.range.first).trimEnd()
                continue
            }

            val noteMatch = noteSuffixRegex.find(working)
            if (noteMatch != null) {
                // Captures to end-of-line: the note is always the last token when written
                // by the app. (A note text that itself ends with a date-like suffix would
                // be peeled first — an accepted edge case for hand-edited lines.)
                note = noteMatch.groupValues[1].trim()
                suffixParts.add(0, working.substring(noteMatch.range.first))
                working = working.substring(0, noteMatch.range.first).trimEnd()
                continue
            }

            break
        }

        return ParsedRemainder(
            mainText = working.trimEnd(),
            suffixRaw = suffixParts.joinToString(separator = ""),
            meta = TasksMeta(
                dueDate = dueDate,
                startDate = startDate,
                scheduledDate = scheduledDate,
                completionDate = completionDate,
                createdDate = createdDate,
                priority = priority,
                recurrence = recurrence,
                note = note,
            ),
        )
    }

    private fun priorityToEmoji(priority: TasksPriority): String {
        return when (priority) {
            TasksPriority.HIGHEST -> "🔺"
            TasksPriority.HIGH -> "⏫"
            TasksPriority.MEDIUM -> "🔼"
            TasksPriority.LOW -> "🔽"
            TasksPriority.LOWEST -> "⏬"
            TasksPriority.NONE -> ""
        }
    }

    private fun emojiToPriority(emoji: String): TasksPriority? {
        return when (emoji) {
            "🔺" -> TasksPriority.HIGHEST
            "⏫" -> TasksPriority.HIGH
            "🔼" -> TasksPriority.MEDIUM
            "🔽" -> TasksPriority.LOW
            "⏬", "⏬️" -> TasksPriority.LOWEST
            else -> null
        }
    }
}
