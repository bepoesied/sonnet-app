package pw.kmr.sonnet.core.ui.component

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SwipeActionBox(
    revealedDirection: SwipeDirection?,
    onRevealedChange: (SwipeDirection?) -> Unit,
    modifier: Modifier = Modifier,
    positionalThreshold: Dp = 48.dp,
    actionWidth: Dp = 96.dp,
    dragDeadZone: Dp = 16.dp,
    startBackgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    endBackgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val thresholdPx = with(density) { positionalThreshold.toPx() }
    val dragDeadZonePx = with(density) { dragDeadZone.toPx() }

    var offsetPx by remember { mutableFloatStateOf(0f) }
    var pendingDragPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(revealedDirection, actionWidthPx) {
        val targetOffset = when (revealedDirection) {
            SwipeDirection.StartToEnd -> actionWidthPx
            SwipeDirection.EndToStart -> -actionWidthPx
            null -> 0f
        }
        if (offsetPx != targetOffset) {
            offsetPx = targetOffset
        }
    }

    fun settle(direction: SwipeDirection?) {
        scope.launch {
            offsetPx = when (direction) {
                SwipeDirection.StartToEnd -> actionWidthPx
                SwipeDirection.EndToStart -> -actionWidthPx
                null -> 0f
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (offsetPx > 0f && startBackgroundContent != null) {
            Box(modifier = Modifier.matchParentSize()) {
                startBackgroundContent()
            }
        }
        if (offsetPx < 0f && endBackgroundContent != null) {
            Box(modifier = Modifier.matchParentSize()) {
                endBackgroundContent()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = offsetPx.roundToInt(), y = 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        if (revealedDirection == null && offsetPx == 0f) {
                            pendingDragPx += delta
                            offsetPx = if (pendingDragPx > 0f && startBackgroundContent != null) {
                                (pendingDragPx - dragDeadZonePx).coerceIn(0f, actionWidthPx)
                            } else if (pendingDragPx < 0f && endBackgroundContent != null) {
                                (pendingDragPx + dragDeadZonePx).coerceIn(-actionWidthPx, 0f)
                            } else {
                                0f
                            }
                        } else {
                            offsetPx = (offsetPx + delta).coerceIn(
                                minimumValue = if (endBackgroundContent == null) 0f else -actionWidthPx,
                                maximumValue = if (startBackgroundContent == null) 0f else actionWidthPx
                            )
                        }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        pendingDragPx = 0f
                        val direction = when {
                            offsetPx >= thresholdPx -> SwipeDirection.StartToEnd
                            offsetPx <= -thresholdPx -> SwipeDirection.EndToStart
                            else -> null
                        }
                        onRevealedChange(direction)
                        settle(direction)
                    }
                ),
            content = content
        )
    }
}

enum class SwipeDirection {
    StartToEnd,
    EndToStart
}
