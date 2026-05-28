        items(10) {
        Column(
            modifier = Modifier
                .weight(1f)
                .shimmer()
        ) {
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
            LibraryContent(
                uiState = uiState,
                onBookClick = onOpenPlayer,
                onSwipeCompletionAction = viewModel::swipeCompletionAction,
                onSwipeDownloadAction = viewModel::swipeDownloadAction,
                modifier = Modifier.fillMaxSize()
            )
        !uiState.initialLoadComplete -> LibraryShimmerPlaceholder(modifier = modifier)
        uiState.books.isEmpty() && uiState.isRefreshing -> LibraryShimmerPlaceholder(modifier = modifier)
private fun LibraryShimmerPlaceholder(modifier: Modifier = Modifier) {
    val shimmerTheme = ShimmerTheme.default
    val shimmerInstance = remember { shimmerTheme.shimmerInstance() }

    LazyColumn(
        modifier = modifier.shimmer(shimmerInstance),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        items(6) {
            ShimmerRow()
            HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
        }
private fun ShimmerRow(modifier: Modifier = Modifier) {
    val shimmerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val cornerShape = RoundedCornerShape(4.dp)

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(shimmerColor)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(20.dp)
                    .clip(cornerShape)
                    .background(shimmerColor)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(16.dp)
                    .clip(cornerShape)
                    .background(shimmerColor)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(14.dp)
                    .clip(cornerShape)
                    .background(shimmerColor)
            )
        }
    }
}

@Composable
                    text = book.completionActionLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = book.completionActionContentColor()
                )
            }
        },
        endBackgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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

                    revealedBookId = null
                    onSwipeDownloadAction(book)
                },
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
        revealed = revealed,
        onRevealedChange = onRevealedChange,
                    .clickable(onClick = onSwipeDownloadAction)
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(book.downloadStatus.actionColor())
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = book.downloadStatus.actionLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = book.downloadStatus.actionContentColor()
                )
                    )
                }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = if (book.downloadStatus == DownloadStatus.Downloading) "Cancel" else "Delete",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
    ) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = book.isDownloaded, onClick = onClick)
            if (!book.isDownloaded) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDownload,
                    enabled = book.downloadStatus != DownloadStatus.Downloading && book.downloadStatus != DownloadStatus.Queued
                ) {
                    Text(text = if (book.downloadStatus == DownloadStatus.Failed) "Retry" else "Download")
                }
            }
    }
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
@OptIn(ExperimentalMaterial3Api::class)
    repository: LibraryRepository,
    onOpenPlayer: (LibraryBook) -> Unit,
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(repository))
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
            LibraryContent(
                uiState = uiState,
                onBookClick = onOpenPlayer,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    onBookClick: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        uiState.books.isEmpty() && uiState.isRefreshing -> LoadingLibrary(modifier = modifier)
        uiState.books.isEmpty() -> EmptyLibrary(lastRefreshFailed = uiState.lastRefreshFailed, modifier = modifier)
        else -> LibraryList(books = uiState.books, onBookClick = onBookClick, modifier = modifier)
    }
}

@Composable
private fun LibraryList(
    books: List<LibraryBook>,
    onBookClick: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        items(items = books, key = { it.id }) { book ->
            LibraryRow(
                book = book,
                onClick = { onBookClick(book) },
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
        }
    }
}

@Composable
private fun LibraryRow(
    book: LibraryBook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(book = book)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
                text = book.author ?: "Unknown author",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (book.isCompleted) "Completed" else "In progress",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = book.downloadStatus.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = book.downloadStatus.color()
                )
            if (book.downloadStatus == DownloadStatus.Downloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.downloadProgress() ?: 0f },
                    modifier = Modifier.fillMaxWidth()
                )

@Composable
private fun CoverImage(book: LibraryBook, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageData = book.localCoverUri ?: book.remoteCoverUrl
    val stableCacheKey = "book-cover-${book.id}"

    Box(
        modifier = modifier
            .size(width = 68.dp, height = 92.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageData == null) {
            Text(
                text = book.title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageData)
                    .memoryCacheKey(stableCacheKey)
                    .diskCacheKey(stableCacheKey)
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
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyLibrary(lastRefreshFailed: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = if (lastRefreshFailed) "Library unavailable" else "No books yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = if (lastRefreshFailed) {
                "Pull to retry. Downloaded books will still appear here when available offline."
            } else {
                "Pull to refresh your Sonnet library."
            },
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.NotDownloaded -> "Not downloaded"
    DownloadStatus.Queued -> "Queued"
    DownloadStatus.Downloading -> "Downloading"
    DownloadStatus.Downloaded -> "Downloaded"
    DownloadStatus.Failed -> "Download failed"
}

@Composable
private fun DownloadStatus.color() = when (this) {
    DownloadStatus.Downloaded -> MaterialTheme.colorScheme.primary
    DownloadStatus.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun LibraryBook.downloadProgress(): Float? {
    val total = totalBytes ?: return null
    if (total <= 0L) return null
    return (downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
}
@Composable
fun LibraryRoute(
    onOpenPlayer: () -> Unit,
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
                text = "Library",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Your synced and downloaded audiobooks will appear here.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onOpenPlayer,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text(text = "Open player")
            }
        }
    }
}