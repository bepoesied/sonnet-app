package pw.kmr.sonnet.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import pw.kmr.sonnet.core.ui.component.SwipeActionBox
import pw.kmr.sonnet.core.ui.component.SwipeDirection
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.library.LibraryUiState
import pw.kmr.sonnet.shared.library.LibraryViewModel
import pw.kmr.sonnet.shared.library.libraryViewModelFactory
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.LibraryBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    repository: LibraryRepository,
    onOpenPlayer: (LibraryBook) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: LibraryViewModel = viewModel(factory = libraryViewModelFactory(repository))
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
                title = { Text(text = "Library") },
                actions = { TextButton(onClick = onLogout) { Text(text = "Log out") } }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            LibraryContent(
                uiState = uiState,
                onBookClick = onOpenPlayer,
                onSwipeCompletionAction = viewModel::swipeCompletionAction,
                onSwipeDownloadAction = viewModel::swipeDownloadAction,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    onBookClick: (LibraryBook) -> Unit,
    onSwipeCompletionAction: (LibraryBook) -> Unit,
    onSwipeDownloadAction: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        !uiState.initialLoadComplete -> LoadingLibrary(modifier = modifier)
        uiState.books.isEmpty() && uiState.isRefreshing -> LoadingLibrary(modifier = modifier)
        uiState.books.isEmpty() -> EmptyLibrary(
            lastRefreshFailed = uiState.lastRefreshFailed,
            modifier = modifier
        )

        else -> LibraryList(
            books = uiState.books,
            onBookClick = onBookClick,
            onSwipeCompletionAction = onSwipeCompletionAction,
            onSwipeDownloadAction = onSwipeDownloadAction,
            modifier = modifier
        )
    }
}

@Composable
private fun LibraryList(
    books: List<LibraryBook>,
    onBookClick: (LibraryBook) -> Unit,
    onSwipeCompletionAction: (LibraryBook) -> Unit,
    onSwipeDownloadAction: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier
) {
    var revealedAction by remember { mutableStateOf<RevealedLibraryAction?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        items(books, key = { it.id }) { book ->
            LibraryRow(
                book = book,
                onClick = { onBookClick(book) },
                revealedDirection = revealedAction
                    ?.takeIf { it.bookId == book.id }
                    ?.direction,
                onRevealedChange = { direction ->
                    revealedAction = if (direction == null) null else RevealedLibraryAction(book.id, direction)
                },
                onSwipeCompletionAction = {
                    revealedAction = null
                    onSwipeCompletionAction(book)
                },
                onSwipeDownloadAction = {
                    revealedAction = null
                    onSwipeDownloadAction(book)
                }
            )
            HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
        }
    }
}

@Composable
private fun LibraryRow(
    book: LibraryBook,
    onClick: () -> Unit,
    revealedDirection: SwipeDirection?,
    onRevealedChange: (SwipeDirection?) -> Unit,
    onSwipeCompletionAction: () -> Unit,
    onSwipeDownloadAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    SwipeActionBox(
        revealedDirection = revealedDirection,
        onRevealedChange = onRevealedChange,
        modifier = modifier,
        startBackgroundContent = {
            CompletionActionBackground(book = book, onClick = onSwipeCompletionAction)
        },
        endBackgroundContent = {
            DownloadActionBackground(book = book, onClick = onSwipeDownloadAction)
        }
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .clickable(enabled = book.isDownloaded, onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = CenterVertically
        ) {
            CoverImage(book = book, modifier = Modifier.size(72.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!book.author.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = book.author.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = CenterVertically) {
                    Text(
                        text = if (book.isCompleted) "Completed" else "In progress",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (book.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = book.downloadStatus.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = book.downloadStatus.color()
                    )
                }
                if (book.downloadStatus == DownloadStatus.Downloading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { book.downloadProgress() ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!book.isDownloaded) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Swipe left to ${book.downloadStatus.actionLabel().lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionActionBackground(book: LibraryBook, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(book.completionActionColor())
            .clickable(onClick = onClick)
            .padding(start = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = book.completionActionLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = book.completionActionContentColor()
        )
    }
}

@Composable
private fun DownloadActionBackground(book: LibraryBook, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(book.downloadStatus.actionColor())
            .clickable(onClick = onClick)
            .padding(end = 24.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = book.downloadStatus.actionLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = book.downloadStatus.actionContentColor()
        )
    }
}

@Composable
private fun CoverImage(book: LibraryBook, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val cover = book.localCoverUri ?: book.remoteCoverUrl
        if (cover == null) {
            Text(
                text = book.title.firstOrNull()?.toString()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(cover)
                    .diskCacheKey("cover-${book.id}")
                    .memoryCacheKey("cover-${book.id}")
                    .crossfade(true)
                    .build(),
                contentDescription = "${book.title} cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LoadingLibrary(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyLibrary(
    lastRefreshFailed: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (lastRefreshFailed) "Library refresh failed." else "No books yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class RevealedLibraryAction(
    val bookId: String,
    val direction: SwipeDirection
)

private fun LibraryBook.completionActionLabel(): String = if (isCompleted) "Unplayed" else "Played"

@Composable
private fun LibraryBook.completionActionColor() = if (isCompleted) {
    MaterialTheme.colorScheme.secondaryContainer
} else {
    MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun LibraryBook.completionActionContentColor() = if (isCompleted) {
    MaterialTheme.colorScheme.onSecondaryContainer
} else {
    MaterialTheme.colorScheme.onPrimaryContainer
}

private fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.NotDownloaded -> "Not downloaded"
    DownloadStatus.Queued -> "Queued"
    DownloadStatus.Downloading -> "Downloading"
    DownloadStatus.Downloaded -> "Downloaded"
    DownloadStatus.Failed -> "Failed"
}

@Composable
private fun DownloadStatus.color() = when (this) {
    DownloadStatus.NotDownloaded -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.Queued -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.Downloading -> MaterialTheme.colorScheme.primary
    DownloadStatus.Downloaded -> MaterialTheme.colorScheme.primary
    DownloadStatus.Failed -> MaterialTheme.colorScheme.error
}

private fun DownloadStatus.actionLabel(): String = when (this) {
    DownloadStatus.NotDownloaded -> "Download"
    DownloadStatus.Failed -> "Retry"
    DownloadStatus.Queued -> "Cancel"
    DownloadStatus.Downloading -> "Cancel"
    DownloadStatus.Downloaded -> "Delete"
}

@Composable
private fun DownloadStatus.actionColor() = when (this) {
    DownloadStatus.NotDownloaded,
    DownloadStatus.Failed -> MaterialTheme.colorScheme.primaryContainer

    DownloadStatus.Queued,
    DownloadStatus.Downloading,
    DownloadStatus.Downloaded -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun DownloadStatus.actionContentColor() = when (this) {
    DownloadStatus.NotDownloaded,
    DownloadStatus.Failed -> MaterialTheme.colorScheme.onPrimaryContainer

    DownloadStatus.Queued,
    DownloadStatus.Downloading,
    DownloadStatus.Downloaded -> MaterialTheme.colorScheme.onErrorContainer
}

private fun LibraryBook.downloadProgress(): Float? {
    val total = totalChapters?.takeIf { it > 0L } ?: return progressPercent
    return (downloadedChapters.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
