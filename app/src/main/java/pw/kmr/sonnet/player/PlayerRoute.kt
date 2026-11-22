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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import pw.kmr.sonnet.R
import pw.kmr.sonnet.core.ui.component.BookCover
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlayerChapter
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.playback.ProgressResumePrompt
import pw.kmr.sonnet.shared.playback.SleepTimerState

@Composable
fun PlayerRoute(
    bookId: String,
    isDownloaded: Boolean,
    playbackOrchestrator: PlaybackOrchestrator,
    onBack: () -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val factory = remember(playbackOrchestrator, bookId) {
        PlayerViewModel.Factory(playbackOrchestrator, bookId)
    }
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$bookId",
        factory = factory
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        viewModel.load()
    }

    PlayerScreen(
        bookId = bookId,
        isDownloaded = isDownloaded,
        uiState = uiState,
        onBack = onBack,
        onPlayPause = viewModel::playPause,
        onSeekBack = viewModel::seekBack,
        onSeekForward = viewModel::seekForward,
        onSeekTo = viewModel::seekTo,
        onJumpToChapter = viewModel::jumpToChapter,
        onSetSleepTimer = viewModel::setSleepTimer,
        onUseRemoteProgress = viewModel::useRemoteProgress,
        onKeepLocalProgress = viewModel::keepLocalProgress,
        onClearError = viewModel::clearError,
        onDialogVisibilityChange = onDialogVisibilityChange,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreen(
    bookId: String,
    isDownloaded: Boolean,
    uiState: PlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onJumpToChapter: (String) -> Unit,
    onSetSleepTimer: (SleepTimerState) -> Unit,
    onUseRemoteProgress: () -> Unit,
    onKeepLocalProgress: () -> Unit,
    onClearError: () -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isUnavailable = uiState.bookMissing ||
        (uiState.errorMessage != null && !uiState.canPlay && uiState.chapters.isEmpty())

    LaunchedEffect(uiState.errorMessage, isUnavailable) {
        if (isUnavailable) return@LaunchedEffect
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onClearError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.player_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_minimize_cd)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        if (!isDownloaded) {
            NotDownloaded(
                onBack = onBack,
                contentPadding = contentPadding
            )
        } else if (isUnavailable) {
            BookUnavailable(
                message = uiState.errorMessage ?: stringResource(R.string.player_book_unavailable_fallback),
                onBack = onBack,
                contentPadding = contentPadding
            )
        } else {
            PlayerContent(
                bookId = bookId,
                uiState = uiState,
                onPlayPause = onPlayPause,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                onSeekTo = onSeekTo,
                onJumpToChapter = onJumpToChapter,
                onSetSleepTimer = onSetSleepTimer,
                onUseRemoteProgress = onUseRemoteProgress,
                onKeepLocalProgress = onKeepLocalProgress,
                onDialogVisibilityChange = onDialogVisibilityChange,
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
        title = { Text(text = stringResource(R.string.player_resume_dialog_title)) },
        text = {
            Text(
                text = if (prompt.remoteIsCompleted) {
                    stringResource(
                        R.string.player_resume_dialog_completed_message,
                        prompt.localChapterTitle,
                        prompt.localChapterOffsetMs.formatDuration()
                    )
                } else {
                    stringResource(
                        R.string.player_resume_dialog_progress_message,
                        prompt.remoteChapterTitle,
                        prompt.remoteChapterOffsetMs.formatDuration(),
                        prompt.localChapterTitle,
                        prompt.localChapterOffsetMs.formatDuration()
                    )
                }
            )
        },
        confirmButton = { TextButton(onClick = onUseRemote) { Text(text = stringResource(R.string.player_resume_use_server)) } },
        dismissButton = { TextButton(onClick = onKeepLocal) { Text(text = stringResource(R.string.player_resume_keep_local)) } }
    )
}

@Composable
private fun NotDownloaded(
    onBack: () -> Unit,
    contentPadding: PaddingValues
) {
    BookUnavailable(
        message = stringResource(R.string.player_not_downloaded),
        onBack = onBack,
        contentPadding = contentPadding
    )
}

@Composable
private fun BookUnavailable(
    message: String,
    onBack: () -> Unit,
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        FilledTonalButton(onClick = onBack) {
            Text(text = stringResource(R.string.player_back_to_library))
        }
    }
}

@Composable
private fun PlayerContent(
    bookId: String,
    uiState: PlayerUiState,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onJumpToChapter: (String) -> Unit,
    onSetSleepTimer: (SleepTimerState) -> Unit,
    onUseRemoteProgress: () -> Unit,
    onKeepLocalProgress: () -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showChapters by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }

    val resumePrompt = uiState.resumePrompt
    // Dialogs and sheets use their own window/back handling. While one is shown the app-level
    // player gesture must not also dismiss the sheet behind it.
    SideEffect { onDialogVisibilityChange(resumePrompt != null || showChapters || showSleepTimer) }
    if (resumePrompt != null) {
        ResumeProgressDialog(
            prompt = resumePrompt,
            onUseRemote = onUseRemoteProgress,
            onKeepLocal = onKeepLocalProgress
        )
    }

    if (showChapters) {
        ChapterSheet(
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
        SleepTimerSheet(
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BookCover(
            bookId = uiState.bookId ?: bookId,
            title = uiState.title,
            localCoverPath = uiState.coverFilePath,
            shape = RoundedCornerShape(18.dp),
            monogramStyle = MaterialTheme.typography.displayMedium,
            crossfade = false,
            modifier = Modifier.size(220.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = uiState.title.ifBlank { stringResource(R.string.player_loading_book) },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = listOfNotNull(uiState.author, uiState.currentChapterTitle.takeIf { it.isNotBlank() })
                .joinToString(" • ")
                .ifBlank { stringResource(R.string.player_preparing_chapters) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(24.dp))
        PlayerSeekBar(
            chapterId = uiState.currentChapterId,
            chapterStartPositionMs = uiState.currentChapterStartPositionMs,
            chapterPositionMs = uiState.currentChapterPositionMs,
            chapterDurationMs = uiState.currentChapterDurationMs,
            onSeekTo = onSeekTo
        )
        Spacer(modifier = Modifier.height(28.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = onSeekBack,
                enabled = uiState.canPlay,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Replay10,
                    contentDescription = stringResource(R.string.player_seek_back_cd),
                    modifier = Modifier.size(36.dp)
                )
            }
            FilledIconButton(
                onClick = onPlayPause,
                enabled = uiState.canPlay,
                modifier = Modifier.size(92.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (uiState.isPlaying) R.string.player_pause_cd else R.string.player_play_cd
                    ),
                    modifier = Modifier.size(48.dp)
                )
            }
            FilledTonalIconButton(
                onClick = onSeekForward,
                enabled = uiState.canPlay,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Forward10,
                    contentDescription = stringResource(R.string.player_seek_forward_cd),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.Top
        ) {
            val chaptersLabel = stringResource(R.string.player_chapters)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = { showChapters = true },
                    enabled = uiState.chapters.isNotEmpty(),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = chaptersLabel,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = chaptersLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val sleepTimerActive = uiState.sleepTimer !is SleepTimerState.Off
            val sleepTimerLabel = uiState.sleepTimer.localizedLabel()
            val sleepTint = if (sleepTimerActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = { showSleepTimer = true },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = sleepTimerLabel,
                        tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = if (sleepTimerActive) sleepTimerLabel else stringResource(R.string.player_sleep),
                    style = MaterialTheme.typography.labelMedium,
                    color = sleepTint
                )
            }
        }
    }
}

@Composable
internal fun PlayerSeekBar(
    chapterId: String?,
    chapterStartPositionMs: Long,
    chapterPositionMs: Long,
    chapterDurationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDurationMs = max(chapterDurationMs, 1L)
    val trackRange = 0f..safeDurationMs.toFloat()
    var dragPositionMs by remember(chapterId) { mutableStateOf<Long?>(null) }
    val displayedPositionMs = (dragPositionMs ?: chapterPositionMs).coerceIn(0L, safeDurationMs)

    val sliderState = remember(trackRange) {
        SliderState(
            value = displayedPositionMs.toFloat(),
            trackRange = trackRange
        )
    }
    if (dragPositionMs != null) {
        sliderState.value = displayedPositionMs.toFloat()
    } else if (!sliderState.isDragging) {
        sliderState.value = displayedPositionMs.toFloat()
    }

    val playbackPositionDescription = stringResource(R.string.player_playback_position_cd)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            state = sliderState,
            onValueChange = { newValue ->
                val clamped = newValue.roundToLong().coerceIn(0L, safeDurationMs)
                dragPositionMs = clamped
                sliderState.value = clamped.toFloat()
            },
            onValueChangeFinished = {
                val targetPositionMs = dragPositionMs ?: sliderState.value.roundToLong()
                onSeekTo(chapterStartPositionMs + targetPositionMs)
                dragPositionMs = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = playbackPositionDescription }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayedPositionMs.formatDuration(),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = chapterDurationMs.formatDuration(),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    chapters: List<PlayerChapter>,
    currentChapterId: String?,
    onDismiss: () -> Unit,
    onJumpToChapter: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(currentIndex - 1, 0))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            text = stringResource(R.string.player_chapters),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        HorizontalDivider()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                val selected = chapter.id == currentChapterId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpToChapter(chapter.id) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
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
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = chapter.startPositionMs.formatDuration(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.player_current_chapter_cd),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (index < chapters.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    currentTimer: SleepTimerState,
    onDismiss: () -> Unit,
    onSetSleepTimer: (SleepTimerState) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val presetOptions = listOf(
        SleepTimerOption(stringResource(R.string.player_sleep_timer_off), SleepTimerState.Off),
        SleepTimerOption(stringResource(R.string.player_sleep_timer_end_of_chapter), SleepTimerState.ChapterEnd),
        SleepTimerOption(
            pluralStringResource(R.plurals.player_sleep_timer_minutes, 15, 15),
            SleepTimerState.Countdown(15 * 60_000L)
        ),
        SleepTimerOption(
            pluralStringResource(R.plurals.player_sleep_timer_minutes, 30, 30),
            SleepTimerState.Countdown(30 * 60_000L)
        ),
        SleepTimerOption(
            pluralStringResource(R.plurals.player_sleep_timer_minutes, 60, 60),
            SleepTimerState.Countdown(60 * 60_000L)
        )
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.player_sleep_timer_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (currentTimer is SleepTimerState.Countdown) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(
                                R.string.player_sleep_timer_active_remaining,
                                currentTimer.localizedLabel()
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.player_sleep_timer_active_cd),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            presetOptions.forEach { option ->
                val isSelected = when (option.state) {
                    SleepTimerState.Off -> currentTimer is SleepTimerState.Off
                    SleepTimerState.ChapterEnd -> currentTimer is SleepTimerState.ChapterEnd
                    is SleepTimerState.Countdown -> currentTimer == option.state
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetSleepTimer(option.state) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.player_sleep_timer_selected_cd),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private data class SleepTimerOption(
    val label: String,
    val state: SleepTimerState
)

@Composable
private fun SleepTimerState.localizedLabel(): String = when (this) {
    SleepTimerState.Off -> stringResource(R.string.player_sleep_timer_off)
    SleepTimerState.ChapterEnd -> stringResource(R.string.player_sleep_timer_end_of_chapter)
    is SleepTimerState.Countdown -> formatSleepTimerCountdown(remainingMs)
}

internal fun SleepTimerState.formatLabel(): String = when (this) {
    SleepTimerState.Off -> "Off"
    SleepTimerState.ChapterEnd -> "End of chapter"
    is SleepTimerState.Countdown -> formatSleepTimerCountdown(remainingMs)
}

internal fun formatSleepTimerCountdown(remainingMs: Long): String {
    val totalSeconds = ceil(remainingMs.coerceAtLeast(0L) / 1000.0).toLong()
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun Long.formatDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
