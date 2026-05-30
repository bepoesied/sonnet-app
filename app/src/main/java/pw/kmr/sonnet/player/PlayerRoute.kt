package pw.kmr.sonnet.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.max
import kotlin.math.roundToLong
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlayerChapter
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.playback.ProgressResumePrompt
import pw.kmr.sonnet.shared.playback.SleepTimerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerRoute(
    bookId: String,
    isDownloaded: Boolean,
    playbackOrchestrator: PlaybackOrchestrator,
    onBack: () -> Unit,
    coverModifier: Modifier = Modifier,
    playButtonModifier: Modifier = Modifier,
    progressModifier: Modifier = Modifier,
    surfaceBoundsModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$bookId",
        factory = PlayerViewModel.Factory(playbackOrchestrator, bookId)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(bookId) {
        viewModel.load()
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "Player") },
                navigationIcon = { TextButton(onClick = onBack) { Text(text = "Back") } }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        if (!isDownloaded) {
            NotDownloaded(contentPadding = contentPadding)
        } else {
            PlayerContent(
                uiState = uiState,
                onPlayPause = viewModel::playPause,
                onSeekBack = viewModel::seekBack,
                onSeekForward = viewModel::seekForward,
                onSeekTo = viewModel::seekTo,
                onJumpToChapter = viewModel::jumpToChapter,
                onSetSleepTimer = viewModel::setSleepTimer,
                onUseRemoteProgress = viewModel::useRemoteProgress,
                onKeepLocalProgress = viewModel::keepLocalProgress,
                coverModifier = coverModifier,
                playButtonModifier = playButtonModifier,
                progressModifier = progressModifier,
                surfaceBoundsModifier = surfaceBoundsModifier,
                modifier = Modifier.padding(contentPadding)
            )
        }
    }
}

@Composable
private fun ResumeProgressDialog(
    prompt: ProgressResumePrompt,
    onUseRemote: () -> Unit,
    onKeepLocal: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepLocal,
        title = { Text(text = "Resume from server?") },
        text = {
            Text(
                text = "Server has newer progress in \"${prompt.remoteChapterTitle}\" " +
                    "at ${prompt.remoteChapterOffsetMs.formatDuration()}.\n" +
                    "Your local position is in \"${prompt.localChapterTitle}\" " +
                    "at ${prompt.localChapterOffsetMs.formatDuration()}."
            )
        },
        confirmButton = { TextButton(onClick = onUseRemote) { Text(text = "Use server") } },
        dismissButton = { TextButton(onClick = onKeepLocal) { Text(text = "Keep local") } }
    )
}

@Composable
private fun NotDownloaded(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "This book is not downloaded yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayerContent(
    uiState: PlayerUiState,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onJumpToChapter: (String) -> Unit,
    onSetSleepTimer: (SleepTimerState) -> Unit,
    onUseRemoteProgress: () -> Unit,
    onKeepLocalProgress: () -> Unit,
    coverModifier: Modifier = Modifier,
    playButtonModifier: Modifier = Modifier,
    progressModifier: Modifier = Modifier,
    surfaceBoundsModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    var showChapters by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var scrubPosition by remember(uiState.currentChapterId) { mutableLongStateOf(uiState.currentChapterPositionMs) }

    LaunchedEffect(uiState.currentChapterPositionMs) {
        scrubPosition = uiState.currentChapterPositionMs
    }

    val resumePrompt = uiState.resumePrompt
    if (resumePrompt != null) {
        ResumeProgressDialog(
            prompt = resumePrompt,
            onUseRemote = onUseRemoteProgress,
            onKeepLocal = onKeepLocalProgress
        )
    }

    if (showChapters) {
        ChapterDialog(
            chapters = uiState.chapters,
            currentChapterId = uiState.currentChapterId,
            onDismiss = { showChapters = false },
            onJumpToChapter = {
                showChapters = false
                onJumpToChapter(it)
            }
        )
    }

    if (showSleepTimer) {
        SleepTimerDialog(
            currentTimer = uiState.sleepTimer,
            onDismiss = { showSleepTimer = false },
            onSetSleepTimer = {
                showSleepTimer = false
                onSetSleepTimer(it)
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(surfaceBoundsModifier)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlayerCover(
            title = uiState.title,
            coverFilePath = uiState.coverFilePath,
            modifier = coverModifier
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = uiState.title.ifBlank { "Loading book" },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = listOfNotNull(uiState.author, uiState.currentChapterTitle.takeIf { it.isNotBlank() })
                .joinToString(" • ")
                .ifBlank { "Preparing chapters" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(24.dp))
        Slider(
            value = scrubPosition.toFloat(),
            onValueChange = { scrubPosition = it.roundToLong() },
            onValueChangeFinished = { onSeekTo(uiState.currentChapterStartPositionMs + scrubPosition) },
            valueRange = 0f..max(uiState.currentChapterDurationMs, 1L).toFloat(),
            modifier = progressModifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = scrubPosition.formatDuration(), style = MaterialTheme.typography.labelMedium)
            Text(text = uiState.currentChapterDurationMs.formatDuration(), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(modifier = Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = onSeekBack,
                enabled = uiState.canPlay,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(imageVector = Icons.Filled.Replay10, contentDescription = "Seek back 10 seconds", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = onPlayPause,
                enabled = uiState.canPlay,
                modifier = playButtonModifier.size(92.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }
            FilledTonalIconButton(
                onClick = onSeekForward,
                enabled = uiState.canPlay,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(imageVector = Icons.Filled.Forward10, contentDescription = "Seek forward 10 seconds", modifier = Modifier.size(36.dp))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { showChapters = true }, enabled = uiState.chapters.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = "Chapters", modifier = Modifier.size(30.dp))
                }
                Text(text = "Chapters", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { showSleepTimer = true }, modifier = Modifier.size(56.dp)) {
                    Icon(imageVector = Icons.Filled.Timer, contentDescription = uiState.sleepTimer.label(), modifier = Modifier.size(30.dp))
                }
                Text(
                    text = if (uiState.sleepTimer is SleepTimerState.Off) "Sleep" else uiState.sleepTimer.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlayerCover(title: String, coverFilePath: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(220.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverFilePath == null) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(coverFilePath))
                    .memoryCacheKey("player-cover-$coverFilePath")
                    .diskCacheKey("player-cover-$coverFilePath")
                    .build(),
                contentDescription = "$title cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ChapterDialog(
    chapters: List<PlayerChapter>,
    currentChapterId: String?,
    onDismiss: () -> Unit,
    onJumpToChapter: (String) -> Unit
) {
    val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(currentIndex - 1, 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Chapters") },
        text = {
            LazyColumn(state = listState) {
                items(chapters, key = { it.id }) { chapter ->
                    val selected = chapter.id == currentChapterId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToChapter(chapter.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(44.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                                )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = chapter.startPositionMs.formatDuration(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(text = "Close") } }
    )
}

@Composable
private fun SleepTimerDialog(
    currentTimer: SleepTimerState,
    onDismiss: () -> Unit,
    onSetSleepTimer: (SleepTimerState) -> Unit
) {
    val options = listOf(
        SleepTimerState.Off,
        SleepTimerState.ChapterEnd,
        SleepTimerState.Countdown(15 * 60_000L),
        SleepTimerState.Countdown(30 * 60_000L),
        SleepTimerState.Countdown(60 * 60_000L)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Sleep timer") },
        text = {
            Column {
                options.forEach { option ->
                    Text(
                        text = option.label(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetSleepTimer(option) }
                            .padding(vertical = 12.dp),
                        fontWeight = if (option == currentTimer) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(text = "Close") } }
    )
}

private fun SleepTimerState.label(): String = when (this) {
    SleepTimerState.Off -> "Off"
    SleepTimerState.ChapterEnd -> "End of chapter"
    is SleepTimerState.Countdown -> (remainingMs / 60_000L).toString() + " minutes"
}

private fun Long.formatDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        java.lang.String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        java.lang.String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
