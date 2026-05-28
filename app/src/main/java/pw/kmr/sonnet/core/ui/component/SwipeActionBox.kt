    revealedDirection: SwipeDirection?,
    onRevealedChange: (SwipeDirection?) -> Unit,
    startBackgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    endBackgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    LaunchedEffect(revealedDirection, actionWidthPx) {
        val targetOffset = when (revealedDirection) {
            SwipeDirection.StartToEnd -> actionWidthPx
            SwipeDirection.EndToStart -> -actionWidthPx
            null -> 0f
        }
    fun settle(direction: SwipeDirection?) {
                targetValue = when (direction) {
                    SwipeDirection.StartToEnd -> actionWidthPx
                    SwipeDirection.EndToStart -> -actionWidthPx
                    null -> 0f
                }
        if (offsetPx > 0f && startBackgroundContent != null) {
                startBackgroundContent()
        if (offsetPx < 0f && endBackgroundContent != null) {
            Box(modifier = Modifier.matchParentSize()) {
                endBackgroundContent()
            }
        }
                        if (revealedDirection == null && offsetPx == 0f) {
                            pendingDragPx += delta
                                offsetPx = if (pendingDragPx > 0f && startBackgroundContent != null) {
                                    (pendingDragPx - dragDeadZonePx).coerceIn(0f, actionWidthPx)
                                } else if (pendingDragPx < 0f && endBackgroundContent != null) {
                                    (pendingDragPx + dragDeadZonePx).coerceIn(-actionWidthPx, 0f)
                                } else {
                                    0f
                                }
                            offsetPx = (offsetPx + delta).coerceIn(
                                if (endBackgroundContent == null) 0f else -actionWidthPx,
                                if (startBackgroundContent == null) 0f else actionWidthPx
                            )
                        val direction = when {
                            offsetPx >= thresholdPx -> SwipeDirection.StartToEnd
                            offsetPx <= -thresholdPx -> SwipeDirection.EndToStart
                            else -> null
                        }
                        onRevealedChange(direction)
                        settle(direction)

enum class SwipeDirection {
    StartToEnd,
    EndToStart
}
    LaunchedEffect(revealed, actionWidthPx) {
        val targetOffset = if (revealed) -actionWidthPx else 0f
        if (offsetPx != targetOffset) {
            animate(
                initialValue = offsetPx,
                targetValue = targetOffset
            ) { value, _ ->
                offsetPx = value
            }
        }
    }

    fun settle(reveal: Boolean) {
                targetValue = if (reveal) -actionWidthPx else 0f
                        val reveal = offsetPx <= -thresholdPx
                        onRevealedChange(reveal)
                        settle(reveal)
    var offsetPx by remember { mutableFloatStateOf(0f) }
    fun resetOffset() {
        scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = 0f
            ) { value, _ ->
                offsetPx = value
    Box(modifier = modifier.fillMaxWidth()) {
        backgroundContent(if (offsetPx < 0f) SwipeActionBoxValue.EndToStart else SwipeActionBoxValue.Settled)
                .fillMaxWidth()
                .offset { IntOffset(x = offsetPx.roundToInt(), y = 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        offsetPx = (offsetPx + delta).coerceIn(-actionWidthPx, 0f)
                    },
                    onDragStopped = {
                        if (offsetPx <= -thresholdPx) {
                            currentOnEndToStart()
                        }
                        resetOffset()
                    }
            state.anchoredDrag(SwipeActionBoxValue.Settled) { anchors, target ->
                dragTo(anchors.positionOf(target))
            }
        }
    }

    Box(modifier = modifier) {
        backgroundContent(state.targetValue.takeIf { state.offset < 0f } ?: SwipeActionBoxValue.Settled)
        Box(
            modifier = Modifier
                .offset { IntOffset(x = state.offsetOrZero().roundToInt(), y = 0) }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = state,
                        positionalThreshold = { thresholdPx }
                    )
                ),
            content = content
        )
    }

@OptIn(ExperimentalFoundationApi::class)
private fun AnchoredDraggableState<SwipeActionBoxValue>.offsetOrZero(): Float =
    offset.takeUnless { it.isNaN() } ?: 0f
    val currentOnEndToStart by rememberUpdatedState(onEndToStart)
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnEndToStart()
            }
            false
        },
        positionalThreshold = { with(density) { positionalThreshold.toPx() } }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { backgroundContent(dismissState.dismissDirection) },
        modifier = modifier,
        content = content
    )
}