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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.LibraryBook

class LibraryViewModel(
    private val repository: LibraryViewModelRepository
) : ViewModel() {
    private val refreshState = MutableStateFlow(RefreshState())
    private val downloadJobs = mutableMapOf<String, Job>()

    val uiState: StateFlow<LibraryUiState> = combine(repository.libraryItems, refreshState) { books, refresh ->
        LibraryUiState(
            books = books,
            isRefreshing = refresh.isRefreshing,
            lastRefreshFailed = refresh.lastErrorMessage != null,
            errorMessage = refresh.lastErrorMessage,
            initialLoadComplete = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    init {
        resumeInterruptedDownloads()
        refresh()
    }

    fun refresh() {
        if (refreshState.value.isRefreshing) return

        viewModelScope.launch {
            refreshState.value = RefreshState(isRefreshing = true)
            runCatching { repository.refresh() }
                .onSuccess {
                    refreshState.value = RefreshState(isRefreshing = false)
                }
                .onFailure { throwable ->
                    refreshState.value = RefreshState(
                        isRefreshing = false,
                        lastErrorMessage = throwable.message ?: "Library refresh failed."
                    )
                }
        }
    }

    fun clearError() {
        refreshState.value = refreshState.value.copy(lastErrorMessage = null)
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
            runCatching { repository.setCompletion(book, !book.isCompleted) }
                .onFailure { throwable ->
                    refreshState.value = refreshState.value.copy(
                        lastErrorMessage = throwable.message ?: "Unable to update completion state."
                    )
                }
        }
    }

    private fun download(book: LibraryBook) {
        if (downloadJobs.containsKey(book.id)) return

        downloadJobs[book.id] = viewModelScope.launch {
            try {
                repository.downloadBook(book.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                refreshState.value = refreshState.value.copy(
                    lastErrorMessage = throwable.message ?: "Unable to download ${book.title}."
                )
            } finally {
                downloadJobs.remove(book.id)
            }
        }
    }

    private fun cancelOrDeleteDownload(book: LibraryBook) {
        val job = downloadJobs.remove(book.id)
        if (job != null) {
            job.cancel()
            return
        }

        viewModelScope.launch {
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
                        downloadJobs.remove(bookId)
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
    val initialLoadComplete: Boolean = false
)

private data class RefreshState(
    val isRefreshing: Boolean = false,
    val lastErrorMessage: String? = null
)
