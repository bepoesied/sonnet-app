package pw.kmr.sonnet.shared.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.LibraryBook

class LibraryViewModel(
    private val repository: LibraryViewModelRepository
) : ViewModel() {
    private val refreshState = MutableStateFlow(RefreshState())
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadJobsMutex = Mutex()
    private val searchQuery = MutableStateFlow("")
    private var refreshInProgress = false

    // Keep one eagerly collected database stream so startup refresh cannot delay cached content.
    private val localLibrary = repository.libraryItems
        .map { books -> LocalLibraryState(books = books, hasLoaded = true) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LocalLibraryState()
        )

    val uiState: StateFlow<LibraryUiState> = combine(
        localLibrary, refreshState, searchQuery
    ) { local, refresh, query ->
        val filtered = if (query.isBlank()) local.books
        else {
            val lowerQuery = query.lowercase()
            local.books.filter { book ->
                book.title.lowercase().contains(lowerQuery) ||
                    book.author?.lowercase()?.contains(lowerQuery) == true
            }
        }
        LibraryUiState(
            books = filtered,
            isRefreshing = refresh.isRefreshing,
            lastRefreshFailed = refresh.lastErrorMessage != null,
            errorMessage = refresh.lastErrorMessage,
            initialLoadComplete = local.hasLoaded,
            searchQuery = query
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    init {
        resumeInterruptedDownloads()
        refreshAfterLocalLibraryIsLoaded()
    }

    private fun refreshAfterLocalLibraryIsLoaded() {
        viewModelScope.launch {
            localLibrary.first { it.hasLoaded }
            refresh(showProgress = false)
        }
    }

    fun refresh(showProgress: Boolean = true) {
        if (refreshInProgress) return
        refreshInProgress = true

        viewModelScope.launch {
            if (showProgress) refreshState.value = RefreshState(isRefreshing = true)

            runCatching { repository.refresh() }
                .onSuccess {
                    if (showProgress) refreshState.value = RefreshState(isRefreshing = false)
                }
                .onFailure { throwable ->
                    if (showProgress) {
                        refreshState.value = RefreshState(
                            isRefreshing = false,
                            lastErrorMessage = throwable.message ?: "Library refresh failed."
                        )
                    }
                }

            refreshInProgress = false
        }
    }

    fun clearError() {
        refreshState.value = refreshState.value.copy(lastErrorMessage = null)
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun swipeDownloadAction(book: LibraryBook) {
        when (book.downloadStatus) {
            DownloadStatus.NotDownloaded,
            DownloadStatus.Failed -> download(book)

            DownloadStatus.Queued,
            DownloadStatus.Downloading,
            DownloadStatus.Downloaded -> cancelOrDeleteDownload(book)
        }
    }

    fun swipeCompletionAction(book: LibraryBook) {
        viewModelScope.launch {
            try {
                repository.setCompletion(book, !book.isCompleted)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                refreshState.value = refreshState.value.copy(
                    lastErrorMessage = throwable.message ?: "Unable to update completion state."
                )
            }
        }
    }

    private fun download(book: LibraryBook) {
        viewModelScope.launch {
            downloadJobsMutex.withLock {
                if (downloadJobs.containsKey(book.id)) return@launch
                downloadJobs[book.id] = launch {
                    try {
                        repository.downloadBook(book.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        refreshState.value = refreshState.value.copy(
                            lastErrorMessage = throwable.message ?: "Unable to download ${book.title}."
                        )
                    } finally {
                        downloadJobsMutex.withLock { downloadJobs.remove(book.id) }
                    }
                }
            }
        }
    }

    private fun cancelOrDeleteDownload(book: LibraryBook) {
        viewModelScope.launch {
            val job = downloadJobsMutex.withLock { downloadJobs.remove(book.id) }
            if (job != null) {
                job.cancel()
                return@launch
            }

            runCatching { repository.deleteDownload(book.id) }
                .onFailure { throwable ->
                    refreshState.value = refreshState.value.copy(
                        lastErrorMessage = throwable.message ?: "Unable to remove ${book.title}."
                    )
                }
        }
    }

    private fun resumeInterruptedDownloads() {
        viewModelScope.launch {
            repository.interruptedDownloadBookIds().forEach { bookId ->
                downloadJobsMutex.withLock {
                    if (downloadJobs.containsKey(bookId)) return@forEach
                    downloadJobs[bookId] = launch {
                        try {
                            repository.downloadBook(bookId, restartInProgress = true)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            refreshState.value = refreshState.value.copy(
                                lastErrorMessage = throwable.message ?: "Unable to resume a download."
                            )
                        } finally {
                            downloadJobsMutex.withLock { downloadJobs.remove(bookId) }
                        }
                    }
                }
            }
        }
    }
}

fun libraryViewModelFactory(
    repository: LibraryViewModelRepository
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        LibraryViewModel(repository)
    }
}

data class LibraryUiState(
    val books: List<LibraryBook> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    val errorMessage: String? = null,
    val initialLoadComplete: Boolean = false,
    val searchQuery: String = ""
)

private data class RefreshState(
    val isRefreshing: Boolean = false,
    val lastErrorMessage: String? = null
)

private data class LocalLibraryState(
    val books: List<LibraryBook> = emptyList(),
    val hasLoaded: Boolean = false
)
