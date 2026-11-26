package org.recloud.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player

internal data class QueueRow(val key: Int, val title: String, val artist: String)

internal fun Player.queueRows(): List<QueueRow> {
    val timeline = currentTimeline
    val shuffle = shuffleModeEnabled
    var index = timeline.getFirstWindowIndex(shuffle)
    return buildList {
        while (index != C.INDEX_UNSET) {
            val metadata = getMediaItemAt(index).mediaMetadata
            add(QueueRow(index, metadata.title.toString(), metadata.artist?.toString().orEmpty()))
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
    }
}

@Composable
internal fun EditableQueue(
    rows: List<QueueRow>,
    current: Int,
    canMove: Boolean,
    jump: (Int) -> Unit,
    move: (Int, Int) -> Unit,
    remove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<Int?>(null) }
    var target by remember { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val moveRow by rememberUpdatedState(move)
    fun rowAt(y: Float): Int? {
        val visible = list.layoutInfo.visibleItemsInfo
        return visible.firstOrNull { y < it.offset + it.size }?.key as? Int
            ?: visible.lastOrNull()?.key as? Int
    }
    // Continue scrolling while the finger is held near an edge of a long queue.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val viewport = list.layoutInfo
            val edge = (viewport.visibleItemsInfo.firstOrNull()?.size ?: 0) / 2f
            val delta = when {
                fingerY < viewport.viewportStartOffset + edge -> -edge / 5
                fingerY > viewport.viewportEndOffset - edge -> edge / 5
                else -> 0f
            }
            if (delta != 0f && list.scrollBy(delta) != 0f) target = rowAt(fingerY)
        }
    }
    LazyColumn(
        state = list,
        modifier = modifier.pointerInput(rows, canMove) {
            if (!canMove || rows.size < 2) return@pointerInput
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    fingerY = held.position.y
                    val from = rowAt(fingerY) ?: return@awaitEachGesture
                    dragging = from
                    target = from
                    try {
                        // Once held, claim moves before either nested scrolling container handles them.
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == held.id } ?: break
                            if (change.isConsumed || event.changes.size != 1) break
                            change.consume()
                            fingerY = change.position.y
                            target = rowAt(fingerY)
                            if (!change.pressed) {
                                val to = target
                                if (to != null && from != to) moveRow(from, to)
                                break
                            }
                        }
                    } finally {
                        dragging = null
                        target = null
                    }
                }
            } finally {
                dragging = null
                target = null
            }
        },
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            var menu by remember(row) { mutableStateOf(false) }
            val highlight = if (target == row.key) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier
            Row(Modifier.fillMaxWidth().then(highlight), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { jump(row.key) }.padding(vertical = 10.dp)) {
                    Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (row.key == current || row.artist.isNotBlank()) {
                        Text(if (row.key == current) "Now playing" else row.artist, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Box {
                    TextButton({ menu = true }, Modifier.semantics { contentDescription = "Queue actions for ${row.title}" }) { Text("⋮") }
                    DropdownMenu(menu, { menu = false }) {
                        if (canMove) {
                            DropdownMenuItem({ Text("Move up") }, {
                                menu = false
                                move(row.key, rows[index - 1].key)
                            }, enabled = index > 0)
                            DropdownMenuItem({ Text("Move down") }, {
                                menu = false
                                move(row.key, rows[index + 1].key)
                            }, enabled = index < rows.lastIndex)
                        }
                        DropdownMenuItem({ Text("Remove from queue") }, { menu = false; remove(row.key) })
                    }
                }
            }
        }
    }
}
