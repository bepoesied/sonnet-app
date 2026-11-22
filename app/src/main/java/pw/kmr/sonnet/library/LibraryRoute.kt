package pw.kmr.sonnet.library

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import pw.kmr.sonnet.core.ui.component.BookCover
import pw.kmr.sonnet.core.ui.component.SwipeActionBox
import pw.kmr.sonnet.core.ui.component.SwipeDirection
import pw.kmr.sonnet.shared.library.BookDownloadScheduler
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.library.LibraryUiState
import pw.kmr.sonnet.shared.library.LibraryViewModel
import pw.kmr.sonnet.shared.library.libraryViewModelFactory
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.LibraryBook

@Composable
fun LibraryRoute(
    repository: LibraryRepository,
    onOpenPlayer: (LibraryBook) -> Unit,
    onLogout: () -> Unit,
    onReauthenticate: () -> Unit = {},
    requiresReauth: Flow<Boolean> = flowOf(false),
    isOnline: Flow<Boolean> = flowOf(true),
    downloadScheduler: BookDownloadScheduler? = null,
    modifier: Modifier = Modifier
) {
    val factory = remember(repository, requiresReauth, downloadScheduler, isOnline) {
        libraryViewModelFactory(repository, requiresReauth, downloadScheduler, isOnline)
    }
    val viewModel: LibraryViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreen(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onClearError = viewModel::clearError,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onOpenPlayer = onOpenPlayer,
        onCompletionAction = viewModel::swipeCompletionAction,
        onDownloadAction = viewModel::swipeDownloadAction,
        onLogout = onLogout,
        onReauthenticate = onReauthenticate,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenPlayer: (LibraryBook) -> Unit,
    onCompletionAction: (LibraryBook) -> Unit,
    onDownloadAction: (LibraryBook) -> Unit,
    onLogout: () -> Unit,
    onReauthenticate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var bookPendingDelete by remember { mutableStateOf<LibraryBook?>(null) }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onClearError()
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text(text = "Log out?") },
            text = {
                Text(
                    text = "Logging out will remove all downloaded audiobooks from this device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        onLogout()
                    }
                ) {
                    Text(
                        text = "Log out",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    val pendingDelete = bookPendingDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { bookPendingDelete = null },
            title = { Text(text = "Delete download?") },
            text = {
                Text(
                    text = "Remove downloaded audio files for \"${pendingDelete.title}\" from this device? Your listening progress will be kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bookPendingDelete = null
                        onDownloadAction(pendingDelete)
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { bookPendingDelete = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = "Library") },
                actions = {
                    TextButton(onClick = { showLogoutConfirmation = true }) {
                        Text(text = "Log out")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            LibraryContent(
                uiState = uiState,
                onSearchQueryChange = onSearchQueryChange,
                onBookClick = onOpenPlayer,
                onCompletionAction = onCompletionAction,
                onDownloadAction = { book ->
                    if (book.downloadStatus == DownloadStatus.Downloaded) {
                        bookPendingDelete = book
                    } else {
                        onDownloadAction(book)
                    }
                },
                onRetry = onRefresh,
                onReauthenticate = onReauthenticate,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    onSearchQueryChange: (String) -> Unit,
    onBookClick: (LibraryBook) -> Unit,
    onCompletionAction: (LibraryBook) -> Unit,
    onDownloadAction: (LibraryBook) -> Unit,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (uiState.requiresReauth) {
            ReauthBanner(
                onSignInAgain = onReauthenticate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
        if (!uiState.isOnline) {
            OfflineBanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
        // Keep the search bar hoisted in one stable position so focus and IME survive state transitions.
        LibrarySearchBar(
            query = uiState.searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )

        when {
            !uiState.initialLoadComplete -> LibrarySkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            uiState.books.isEmpty() && uiState.isRefreshing && uiState.searchQuery.isBlank() -> LibrarySkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            uiState.books.isEmpty() && uiState.searchQuery.isBlank() -> EmptyLibrary(
                lastRefreshFailed = uiState.lastRefreshFailed,
                isOnline = uiState.isOnline,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            uiState.books.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No matching books.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LibraryList(
                books = uiState.books,
                onBookClick = onBookClick,
                onSwipeCompletionAction = onCompletionAction,
                onSwipeDownloadAction = onDownloadAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun ReauthBanner(
    onSignInAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Session expired. Downloaded books stay playable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSignInAgain) {
                Text(text = "Sign in again")
            }
        }
    }
}

@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Offline — downloaded books remain playable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search books...") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search"
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
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
        itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
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
            if (index < books.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 88.dp))
            }
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
    val downloadActionLabel = book.downloadStatus.actionLabel()
    val completionActionLabel = if (book.isCompleted) "Mark unplayed" else "Mark played"

    SwipeActionBox(
        revealedDirection = revealedDirection,
        onRevealedChange = onRevealedChange,
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(label = downloadActionLabel) {
                    onSwipeDownloadAction()
                    true
                },
                CustomAccessibilityAction(label = completionActionLabel) {
                    onSwipeCompletionAction()
                    true
                }
            )
        },
        startBackgroundContent = {
            CompletionActionBackground(book = book, onClick = onSwipeCompletionAction)
        },
        endBackgroundContent = {
            DownloadActionBackground(book = book, onClick = onSwipeDownloadAction)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(enabled = book.isDownloaded, onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = CenterVertically
        ) {
            BookCover(
                bookId = book.id,
                title = book.title,
                localCoverPath = book.localCoverUri,
                remoteCoverUrl = book.remoteCoverUrl,
                modifier = Modifier.size(72.dp)
            )
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
                    val progress = book.downloadProgress()
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            BookTrailingAction(
                book = book,
                onDownloadAction = onSwipeDownloadAction
            )
        }
    }
}

@Composable
private fun BookTrailingAction(
    book: LibraryBook,
    onDownloadAction: () -> Unit
) {
    when (book.downloadStatus) {
        DownloadStatus.NotDownloaded -> {
            IconButton(onClick = onDownloadAction) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Download ${book.title}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        DownloadStatus.Failed -> {
            IconButton(onClick = onDownloadAction) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Retry download ${book.title}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        DownloadStatus.Queued,
        DownloadStatus.Downloading -> {
            IconButton(onClick = onDownloadAction) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel download ${book.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DownloadStatus.Downloaded -> {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary
                )
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
private fun LibrarySkeleton(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        items(count = 7, key = { it }) { index ->
            Row(
                modifier = Modifier.padding(vertical = 14.dp),
                verticalAlignment = CenterVertically
            ) {
                SkeletonBlock(modifier = Modifier.size(72.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(20.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(16.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(14.dp)
                    )
                }
            }
            if (index < 6) {
                HorizontalDivider(modifier = Modifier.padding(start = 88.dp))
            }
        }
    }
}

/** A Material-themed skeleton block; Compose has no official Material 3 placeholder component. */
@Composable
private fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun EmptyLibrary(
    lastRefreshFailed: Boolean,
    isOnline: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when {
        !isOnline -> Icons.Filled.CloudOff
        lastRefreshFailed -> Icons.Filled.ErrorOutline
        else -> Icons.AutoMirrored.Filled.MenuBook
    }
    val title = when {
        !isOnline -> "You're offline"
        lastRefreshFailed -> "Library refresh failed"
        else -> "No books yet"
    }
    val subtitle = when {
        !isOnline -> "Connect to the internet to load your library."
        lastRefreshFailed -> "Check your connection and try again."
        else -> "Pull down to refresh once audiobooks are available on your server."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (lastRefreshFailed && isOnline) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (lastRefreshFailed || !isOnline) {
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalButton(onClick = onRetry) {
                Text(text = "Retry")
            }
        }
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

internal fun LibraryBook.downloadProgress(): Float? {
    val total = totalChapters?.takeIf { it > 0L } ?: return null
    return (downloadedChapters.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
