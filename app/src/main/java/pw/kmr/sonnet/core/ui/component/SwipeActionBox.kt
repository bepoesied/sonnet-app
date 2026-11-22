package pw.kmr.sonnet.core.ui.component

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

private enum class SwipeAnchor {
    Start,
    Center,
    End
}

private fun SwipeDirection?.toAnchor(): SwipeAnchor = when (this) {
    SwipeDirection.StartToEnd -> SwipeAnchor.Start
    SwipeDirection.EndToStart -> SwipeAnchor.End
    null -> SwipeAnchor.Center
}

private fun SwipeAnchor.toSwipeDirection(): SwipeDirection? = when (this) {
    SwipeAnchor.Start -> SwipeDirection.StartToEnd
    SwipeAnchor.End -> SwipeDirection.EndToStart
    SwipeAnchor.Center -> null
}

@Composable
fun SwipeActionBox(
    revealedDirection: SwipeDirection?,
    onRevealedChange: (SwipeDirection?) -> Unit,
    modifier: Modifier = Modifier,
    positionalThreshold: Dp = 48.dp,
    actionWidth: Dp = 96.dp,
    startBackgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    endBackgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val thresholdPx = with(density) { positionalThreshold.toPx() }
    val hasStartAction = startBackgroundContent != null
    val hasEndAction = endBackgroundContent != null

    val anchors = remember(actionWidthPx, hasStartAction, hasEndAction) {
        DraggableAnchors {
            SwipeAnchor.Center at 0f
            if (hasStartAction) SwipeAnchor.Start at actionWidthPx
            if (hasEndAction) SwipeAnchor.End at -actionWidthPx
        }
    }

    val state = remember {
        AnchoredDraggableState(
            initialValue = revealedDirection.toAnchor(),
            anchors = anchors
        )
    }

    SideEffect {
        state.updateAnchors(anchors)
    }

    val currentRevealedDirection by rememberUpdatedState(revealedDirection)
    val currentOnRevealedChange by rememberUpdatedState(onRevealedChange)

    val targetAnchor = revealedDirection.toAnchor()
    LaunchedEffect(targetAnchor) {
        if (state.settledValue != targetAnchor) {
            state.animateTo(targetAnchor)
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }
            .distinctUntilChanged()
            .collect { settled ->
                val direction = settled.toSwipeDirection()
                if (direction != currentRevealedDirection) {
                    currentOnRevealedChange(direction)
                }
            }
    }

    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = state,
        positionalThreshold = { _ -> thresholdPx },
        animationSpec = spring()
    )

    val showStartBackground by remember(state) {
        derivedStateOf { (state.offset.takeUnless { it.isNaN() } ?: 0f) > 0f }
    }
    val showEndBackground by remember(state) {
        derivedStateOf { (state.offset.takeUnless { it.isNaN() } ?: 0f) < 0f }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        if (showStartBackground && startBackgroundContent != null) {
            Box(modifier = Modifier.matchParentSize()) {
                startBackgroundContent()
            }
        }
        if (showEndBackground && endBackgroundContent != null) {
            Box(modifier = Modifier.matchParentSize()) {
                endBackgroundContent()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    val currentOffset = state.offset.takeUnless { it.isNaN() } ?: 0f
                    IntOffset(x = currentOffset.roundToInt(), y = 0)
                }
                .anchoredDraggable(
                    state = state,
                    reverseDirection = isRtl,
                    orientation = Orientation.Horizontal,
                    enabled = hasStartAction || hasEndAction,
                    flingBehavior = flingBehavior
                ),
            content = content
        )
    }
}

enum class SwipeDirection {
    StartToEnd,
    EndToStart
}
