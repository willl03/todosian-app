package com.isotjs.todosian.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Long-press drag-to-reorder for LazyColumn rows, **indicator style**: the dragged row lifts
 * and follows the finger while the other rows stay put; an accent line marks the gap where the
 * row will land ([DragReorderIndicator]). The actual reorder happens on drop — [onDrop] is
 * called with the original and final indices (only when the position actually changed), and the
 * caller persists the new order.
 *
 * Keys must be the same values the list's `items(key = …)` uses, otherwise rows cannot find
 * themselves in the layout info.
 *
 * Usage:
 * ```
 * val listState = rememberLazyListState()
 * val dragState = remember(listState) {
 *     DragReorderState(listState, scope, onDrop = viewModel::dropFolder)
 * }
 * itemsIndexed(items, key = { _, item -> item.keyString }) { index, item ->
 *     Column(modifier = Modifier.animateItem()) {
 *         RowDragIndicator(state, item.keyString, index)
 *         Row(modifier = Modifier.dragReorderable(dragState, item.keyString))
 *         RowDragIndicator(state, item.keyString, index, belowLast = true)
 *     }
 * }
 * ```
 */
class DragReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onDrop: (fromIndex: Int, toIndex: Int) -> Unit,
    /** Which row keys may serve as drop targets (e.g. exclude a fixed pseudo-row). */
    private val canTarget: (Any) -> Boolean = { true },
    /** Current number of reorderable rows; keeps the insertion index valid when the dragged
     *  row's slot scrolls out of sight during autoscroll. */
    private val reorderableCount: () -> Int = { Int.MAX_VALUE },
) {
    /** Key of the row currently being dragged, or null. Drives the row's lifted look. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    /** Manual Y translation of the dragged row, in pixels (relative to its slot). */
    var dragOffsetPx: Float by mutableStateOf(0f)
        private set

    /** Key of the row an insertion line should be drawn above, or null. */
    var lineAboveKey: Any? by mutableStateOf(null)
        private set

    /** Key of the row an insertion line should be drawn below (last row), or null. */
    var lineBelowKey: Any? by mutableStateOf(null)
        private set

    private var originalIndex = -1
    private var insertionIndex = -1
    private var pendingScrollPx = 0f
    private var autoscrollJob: Job? = null

    fun onDragStart(key: Any) {
        val layout = listState.layoutInfo
        originalIndex = layout.visibleItemsInfo.firstOrNull { it.key == key }?.index ?: -1
        insertionIndex = originalIndex
        draggingKey = key
        dragOffsetPx = 0f
        pendingScrollPx = 0f
        updateIndicator(layout)
        autoscrollJob?.cancel()
        autoscrollJob = scope.launch {
            // Autoscroll near the viewport edges: consume the requested pixels frame by frame
            // and keep the dragged row under the finger (its slot moved by the scroll).
            while (draggingKey != null) {
                val requested = pendingScrollPx
                if (requested != 0f) {
                    pendingScrollPx = 0f
                    var consumed = 0f
                    listState.scroll { consumed = scrollBy(requested) }
                    // Only compensate what actually scrolled: at the list ends the request is
                    // clamped, and compensating for an unconsumed scroll would drift the row.
                    dragOffsetPx += consumed
                }
                delay(AUTOSCROLL_FRAME_MS)
            }
        }
    }

    fun onDrag(key: Any, deltaPx: Float) {
        if (draggingKey != key) return
        dragOffsetPx += deltaPx

        val layout = listState.layoutInfo
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == key }
        if (dragged == null) {
            // The row's slot scrolled out of sight during autoscroll: the finger is pinned at
            // the edge, so clamp the insertion position to that end of the list instead of
            // acting on a stale index.
            val count = reorderableCount()
            if (count > 0) {
                insertionIndex = if (pendingScrollPx > 0f) count - 1 else 0
                updateIndicator(layout)
            }
            return
        }
        val dragCenter = dragged.offset + dragOffsetPx + dragged.size / 2f

        // Insertion position: how many target rows have their center above the finger's
        // midpoint. Only reorderable rows count; the dragged row's own slot does not.
        var k = 0
        for (item in layout.visibleItemsInfo) {
            if (item.key == key || !canTarget(item.key)) continue
            if (item.offset + item.size / 2f < dragCenter) k++
        }
        insertionIndex = k

        updateIndicator(layout)
        requestAutoscroll(layout, dragged.offset, dragged.size)
    }

    fun onDragEnd() {
        val wasDragging = draggingKey != null
        // Capture the indices before the state reset below — the drop callback needs them.
        val from = originalIndex
        val to = insertionIndex
        val drop = wasDragging && from >= 0 && to >= 0 && to != from

        draggingKey = null
        dragOffsetPx = 0f
        lineAboveKey = null
        lineBelowKey = null
        originalIndex = -1
        insertionIndex = -1
        pendingScrollPx = 0f
        autoscrollJob?.cancel()
        autoscrollJob = null
        if (drop) onDrop(from, to)
    }

    private fun updateIndicator(layout: LazyListLayoutInfo) {
        if (draggingKey == null) return

        val rowCount = layout.totalItemsCount
        if (rowCount == 0 || insertionIndex < 0) {
            lineAboveKey = null
            lineBelowKey = null
            return
        }

        // The insertion happens at index [insertionIndex] of the list *without* the dragged
        // row. In the visual (unmoved) layout that gap sits above the row at full index
        // [insertionIndex] when dragging upward, and above [insertionIndex + 1] when dragging
        // downward; past the end of the list it belongs below the last row.
        val aboveFull = if (insertionIndex < originalIndex) insertionIndex else insertionIndex + 1
        if (aboveFull >= rowCount) {
            lineAboveKey = null
            lineBelowKey = layout.visibleItemsInfo.lastOrNull { it.index == rowCount - 1 }?.key
        } else {
            lineAboveKey = layout.visibleItemsInfo.firstOrNull { it.index == aboveFull }?.key
            lineBelowKey = null
        }
    }

    private fun requestAutoscroll(layout: LazyListLayoutInfo, draggedOffset: Int, draggedSize: Int) {
        val dragStart = draggedOffset + dragOffsetPx
        val dragEnd = dragStart + draggedSize
        val viewportStart = layout.viewportStartOffset
        val viewportEnd = layout.viewportEndOffset
        pendingScrollPx += when {
            dragEnd > viewportEnd - EDGE_PX ->
                ((dragEnd - (viewportEnd - EDGE_PX)) / EDGE_PX) * SCROLL_SPEED_PX

            dragStart < viewportStart + EDGE_PX ->
                -((viewportStart + EDGE_PX - dragStart) / EDGE_PX) * SCROLL_SPEED_PX

            else -> 0f
        }
    }

    private companion object {
        private const val AUTOSCROLL_FRAME_MS = 16L
        private const val EDGE_PX = 96f
        private const val SCROLL_SPEED_PX = 12f
    }
}

/** Accent line shown in the gap where a dragged row will land. */
@Composable
fun DragReorderIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .height(3.dp)
            .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
    )
}

/**
 * Renders the insertion line above or below one row (as decided by [state]). Wrap each row:
 * [DragReorderIndicatorSlot] above it, the row itself, then the slot below.
 */
@Composable
fun DragReorderIndicatorSlot(
    state: DragReorderState,
    rowKey: Any,
    below: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visible = if (below) state.lineBelowKey == rowKey else state.lineAboveKey == rowKey
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        DragReorderIndicator()
    }
}

/** Long-press drag behavior for one row; pairs with [DragReorderIndicatorSlot] wrappers.
 *  [dragShape] shapes the elevation shadow while the row is lifted (match the row shape). */
fun Modifier.dragReorderable(
    state: DragReorderState,
    itemKey: Any,
    dragShape: Shape? = null,
): Modifier {
    val isDragging = state.draggingKey == itemKey
    return this
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            if (isDragging) {
                translationY = state.dragOffsetPx
                scaleX = 1.02f
                scaleY = 1.02f
                if (dragShape != null) shape = dragShape
                shadowElevation = 16f
            }
        }
        .pointerInput(itemKey) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.onDragStart(itemKey) },
                onDrag = { change, amount ->
                    change.consume()
                    state.onDrag(itemKey, amount.y)
                },
                onDragEnd = { state.onDragEnd() },
                onDragCancel = { state.onDragEnd() },
            )
        }
}

@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    onDrop: (fromIndex: Int, toIndex: Int) -> Unit,
    canTarget: (Any) -> Boolean = { true },
    reorderableCount: () -> Int = { Int.MAX_VALUE },
): DragReorderState {
    val scope = rememberCoroutineScope()
    return remember(listState) { DragReorderState(listState, scope, onDrop, canTarget, reorderableCount) }
}
