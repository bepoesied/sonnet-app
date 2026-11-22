package pw.kmr.sonnet.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import pw.kmr.sonnet.core.ui.component.BookCover
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlayerUiState

private data class MiniPlayerMetadata(
    val bookId: String?,
    val title: String,
    val author: String?,
    val coverFilePath: String?,
    val currentChapterTitle: String,
    val isPlaying: Boolean,
    val canPlay: Boolean
)

private fun PlayerUiState.toMiniPlayerMetadata(): MiniPlayerMetadata = MiniPlayerMetadata(
    bookId = bookId,
    title = title,
    author = author,
    coverFilePath = coverFilePath,
    currentChapterTitle = currentChapterTitle,
    isPlaying = isPlaying,
    canPlay = canPlay
)

@Composable
internal fun MiniPlayerHost(
    playbackOrchestrator: PlaybackOrchestrator,
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val metadataFlow = remember(playbackOrchestrator) {
        playbackOrchestrator.state
            .map { it.toMiniPlayerMetadata() }
            .distinctUntilChanged()
    }
    val metadata by metadataFlow.collectAsStateWithLifecycle(
        initialValue = playbackOrchestrator.state.value.toMiniPlayerMetadata()
    )
    val bookId = metadata.bookId ?: return

    MiniPlayerContent(
        metadata = metadata,
        onClick = { onOpenPlayer(bookId) },
        onPlayPause = playbackOrchestrator::playPause,
        enabled = enabled,
        modifier = modifier,
        progressBar = {
            MiniPlayerProgress(stateFlow = playbackOrchestrator.state)
        }
    )
}

@Composable
private fun MiniPlayerProgress(stateFlow: StateFlow<PlayerUiState>) {
    val progressFlow = remember(stateFlow) {
        stateFlow
            .map { state ->
                (state.currentChapterPositionMs.toFloat() /
                    state.currentChapterDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            }
            .distinctUntilChanged()
    }
    val progress by progressFlow.collectAsStateWithLifecycle(
        initialValue = (stateFlow.value.currentChapterPositionMs.toFloat() /
            stateFlow.value.currentChapterDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun MiniPlayer(
    uiState: PlayerUiState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    MiniPlayerContent(
        metadata = uiState.toMiniPlayerMetadata(),
        onClick = onClick,
        onPlayPause = onPlayPause,
        enabled = enabled,
        modifier = modifier,
        progressBar = {
            LinearProgressIndicator(
                progress = {
                    (uiState.currentChapterPositionMs.toFloat() /
                        uiState.currentChapterDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
private fun MiniPlayerContent(
    metadata: MiniPlayerMetadata,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    enabled: Boolean,
    progressBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            progressBar()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BookCover(
                    bookId = metadata.bookId,
                    title = metadata.title,
                    localCoverPath = metadata.coverFilePath,
                    crossfade = false,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = metadata.title.ifBlank { "Loading book" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metadata.currentChapterTitle.ifBlank { metadata.author ?: "Preparing chapters" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    enabled = enabled && metadata.canPlay
                ) {
                    Icon(
                        imageVector = if (metadata.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (metadata.isPlaying) "Pause" else "Play"
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
