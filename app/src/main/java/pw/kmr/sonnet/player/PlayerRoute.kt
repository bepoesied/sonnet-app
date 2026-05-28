    LaunchedEffect(bookId) {
        viewModel.load()
    }

    playButtonModifier: Modifier = Modifier,
    progressModifier: Modifier = Modifier,
    surfaceBoundsModifier: Modifier = Modifier,
                playButtonModifier = playButtonModifier,
                progressModifier = progressModifier,
                surfaceBoundsModifier = surfaceBoundsModifier,
    playButtonModifier: Modifier = Modifier,
    progressModifier: Modifier = Modifier,
    surfaceBoundsModifier: Modifier = Modifier,
            .then(surfaceBoundsModifier)
                modifier = progressModifier.fillMaxWidth()
                    modifier = playButtonModifier.size(92.dp)
    coverModifier: Modifier = Modifier,
                modifier = coverModifier
        key = "player-$bookId",
        factory = PlayerViewModel.Factory(playbackController, bookId)
    )
                    "at ${prompt.remoteChapterOffsetMs.formatDuration()}. Your local position is " +
                    "${prompt.localChapterTitle} at ${prompt.localChapterOffsetMs.formatDuration()}."
            prompt = prompt,
            onUseRemote = viewModel::useRemoteProgress,
            onKeepLocal = viewModel::keepLocalProgress
        )
    }

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
                text = "This book has newer server progress at ${prompt.remotePositionMs.formatDuration()}. " +
                    "Your local position is ${prompt.localPositionMs.formatDuration()}."
            )
        },
        confirmButton = { Button(onClick = onUseRemote) { Text(text = "Use server") } },
        dismissButton = { OutlinedButton(onClick = onKeepLocal) { Text(text = "Keep local") } }
    )
}

@Composable
                                .height(44.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                    MaterialTheme.colorScheme.onSurface
                            Text(
                                text = chapter.startPositionMs.formatDuration(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                modifier = Modifier.fillMaxWidth()
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            Spacer(modifier = Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = onSeekBack,
                    enabled = uiState.canPlay,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = "Seek back 10 seconds",
                        modifier = Modifier.size(36.dp)
                    )
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = uiState.canPlay,
                    modifier = Modifier.size(92.dp)
                ) {
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(48.dp)
                FilledTonalIconButton(
                    onClick = onSeekForward,
                    enabled = uiState.canPlay,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = "Seek forward 10 seconds",
                        modifier = Modifier.size(36.dp)
                    )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.Top) {
                    IconButton(
                        onClick = { showChapters = true },
                        enabled = uiState.chapters.isNotEmpty(),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Chapters",
                            modifier = Modifier.size(30.dp)
                        )
                    Text(
                        text = "Chapters",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showSleepTimer = true }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = uiState.sleepTimer.label(),
                            modifier = Modifier.size(30.dp)
                    Text(
                        text = if (uiState.sleepTimer is SleepTimerState.Off) "Sleep" else uiState.sleepTimer.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
            .size(260.dp)
                }
                FilledTonalIconButton(onClick = onSeekForward, enabled = uiState.canPlay) {
                    Icon(imageVector = Icons.Filled.FastForward, contentDescription = "Seek forward 15 seconds")
                }
                IconButton(onClick = { showChapters = true }, enabled = uiState.chapters.isNotEmpty()) {
                    Icon(imageVector = Icons.Filled.List, contentDescription = "Chapters")
                IconButton(onClick = { showSleepTimer = true }) {
                    Icon(imageVector = Icons.Filled.Timer, contentDescription = uiState.sleepTimer.label())
import coil3.request.crossfade
            PlayerCover(
                title = uiState.title,
                coverFilePath = uiState.coverFilePath,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 24.dp)
            )
                text = listOfNotNull(uiState.author, uiState.currentChapterTitle.takeIf { it.isNotBlank() }).joinToString(" • ")
                    .ifBlank { "Preparing chapters" },
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
                    .data(java.io.File(coverFilePath))
                    .memoryCacheKey("player-cover-$coverFilePath")
                    .diskCacheKey("player-cover-$coverFilePath")
                    .crossfade(true)
                    .build(),
                contentDescription = "$title cover",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToLong
@OptIn(ExperimentalMaterial3Api::class)
    playbackController: PlaybackController,
    val viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(playbackController, bookId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                modifier = Modifier.padding(contentPadding)
            )
        }
    }
}

@Composable
private fun NotDownloaded(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Not downloaded", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Download this book from the library before playback.",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge
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
    modifier: Modifier = Modifier
) {
    var showChapters by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var scrubPosition by remember(uiState.bookId) { mutableLongStateOf(uiState.positionMs) }
    var isScrubbing by remember { mutableStateOf(false) }
    val displayedPosition = if (isScrubbing) scrubPosition else uiState.positionMs

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
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
                text = uiState.title.ifBlank { "Loading book" },
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
                text = uiState.currentChapterTitle.ifBlank { "Preparing chapters" },
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (uiState.isBuffering) {
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Buffering local audio")
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Slider(
                value = displayedPosition.toFloat(),
                onValueChange = {
                    isScrubbing = true
                    scrubPosition = it.roundToLong()
                onValueChangeFinished = {
                    isScrubbing = false
                    onSeekTo(scrubPosition)
                },
                valueRange = 0f..uiState.durationMs.coerceAtLeast(1L).toFloat(),
                enabled = uiState.canPlay,
                modifier = Modifier.fillMaxWidth()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = displayedPosition.formatDuration())
                Text(text = uiState.durationMs.formatDuration())
            Spacer(modifier = Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onSeekBack, enabled = uiState.canPlay) { Text(text = "-15s") }
                Button(onClick = onPlayPause, enabled = uiState.canPlay) {
                    Text(text = if (uiState.isPlaying) "Pause" else "Play")
                }
                OutlinedButton(onClick = onSeekForward, enabled = uiState.canPlay) { Text(text = "+15s") }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showChapters = true }, enabled = uiState.chapters.isNotEmpty()) {
                    Text(text = "Chapters")
                }
                OutlinedButton(onClick = { showSleepTimer = true }) {
                    Text(text = uiState.sleepTimer.label())
                }
            }

@Composable
private fun ChapterDialog(
    chapters: List<PlayerChapter>,
    currentChapterId: String?,
    onDismiss: () -> Unit,
    onJumpToChapter: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Chapters") },
        text = {
            LazyColumn {
                items(chapters, key = { it.id }) { chapter ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToChapter(chapter.id) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = chapter.title,
                            fontWeight = if (chapter.id == currentChapterId) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = chapter.startPositionMs.formatDuration(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
        "Off" to SleepTimerState.Off,
        "5 minutes" to SleepTimerState.Countdown(5 * 60_000L),
        "15 minutes" to SleepTimerState.Countdown(15 * 60_000L),
        "30 minutes" to SleepTimerState.Countdown(30 * 60_000L),
        "45 minutes" to SleepTimerState.Countdown(45 * 60_000L),
        "End of chapter" to SleepTimerState.ChapterEnd
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Sleep timer") },
        text = {
            Column {
                Text(
                    text = "Current: ${currentTimer.label()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                options.forEach { (label, timer) ->
                    Text(
                        text = label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetSleepTimer(timer) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(text = "Close") } }
    )
}

private fun SleepTimerState.label(): String = when (this) {
    SleepTimerState.Off -> "Sleep timer"
    SleepTimerState.ChapterEnd -> "End of chapter"
    is SleepTimerState.Countdown -> "Sleep ${remainingMs.formatDuration()}"
}

private fun Long.formatDuration(): String {
    val totalSeconds = (this / 1_000).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
fun PlayerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Player",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Playback controls and Media3 integration will be built here.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onBack,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text(text = "Back to library")
            }
        }
    }
}