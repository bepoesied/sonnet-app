package pw.kmr.sonnet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pw.kmr.sonnet.library.LibraryScreen
import pw.kmr.sonnet.library.downloadProgress
import pw.kmr.sonnet.shared.library.LibraryUiState
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.LibraryBook

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyFailedAndOfflineStatesShowRetryButton() {
        var uiState by mutableStateOf(
            LibraryUiState(
                initialLoadComplete = true,
                books = emptyList(),
                lastRefreshFailed = true,
                isOnline = true
            )
        )
        var retryClicks = 0

        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    uiState = uiState,
                    onRefresh = { retryClicks++ },
                    onClearError = {},
                    onSearchQueryChange = {},
                    onOpenPlayer = {},
                    onCompletionAction = {},
                    onDownloadAction = {},
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("Library refresh failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retryClicks) }

        composeRule.runOnIdle {
            uiState = uiState.copy(isOnline = false, lastRefreshFailed = false)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeRule.onNodeWithText("Offline — downloaded books remain playable.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(2, retryClicks) }
    }

    @Test
    fun searchBarStaysMountedAndFocusedWhenLoadingCompletes() {
        var uiState by mutableStateOf(
            LibraryUiState(
                initialLoadComplete = false,
                books = emptyList(),
                searchQuery = ""
            )
        )

        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    uiState = uiState,
                    onRefresh = {},
                    onClearError = {},
                    onSearchQueryChange = { query -> uiState = uiState.copy(searchQuery = query) },
                    onOpenPlayer = {},
                    onCompletionAction = {},
                    onDownloadAction = {},
                    onLogout = {}
                )
            }
        }

        composeRule.onNodeWithText("Search books...").performClick()
        composeRule.onNodeWithText("Search books...").performTextInput("Dune")
        composeRule.onNodeWithText("Dune").assertIsFocused()

        composeRule.runOnIdle {
            uiState = uiState.copy(
                initialLoadComplete = true,
                isRefreshing = true,
                books = emptyList()
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dune").assertIsFocused()
        composeRule.onNodeWithText("No matching books.").assertIsDisplayed()
    }

    @Test
    fun logoutConfirmationDialogPreventsAccidentalImmediateLogout() {
        var logoutClicks = 0

        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    uiState = LibraryUiState(
                        initialLoadComplete = true,
                        books = listOf(sampleBook(downloadStatus = DownloadStatus.Downloaded))
                    ),
                    onRefresh = {},
                    onClearError = {},
                    onSearchQueryChange = {},
                    onOpenPlayer = {},
                    onCompletionAction = {},
                    onDownloadAction = {},
                    onLogout = { logoutClicks++ }
                )
            }
        }

        composeRule.onNodeWithText("Log out").performClick()
        composeRule.onNodeWithText("Log out?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, logoutClicks) }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Log out?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, logoutClicks) }

        composeRule.onNodeWithText("Log out").performClick()
        composeRule.onNodeWithText("Log out?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Log out").onLast().performClick()

        composeRule.runOnIdle { assertEquals(1, logoutClicks) }
    }

    @Test
    fun downloadProgressReturnsNullWhenTotalChaptersIsUnknown() {
        val unknownChaptersBook = sampleBook(downloadStatus = DownloadStatus.Downloading).copy(
            totalChapters = null,
            downloadedChapters = 0L,
            progressPercent = 0.75f
        )
        assertNull(unknownChaptersBook.downloadProgress())

        val knownChaptersBook = unknownChaptersBook.copy(
            totalChapters = 10L,
            downloadedChapters = 4L
        )
        assertEquals(0.4f, knownChaptersBook.downloadProgress() ?: -1f, 0.001f)
    }

    private fun sampleBook(
        id: String = "book-1",
        title: String = "Dune",
        downloadStatus: DownloadStatus = DownloadStatus.NotDownloaded
    ): LibraryBook = LibraryBook(
        id = id,
        title = title,
        author = "Frank Herbert",
        isCompleted = false,
        isDownloaded = downloadStatus == DownloadStatus.Downloaded,
        downloadStatus = downloadStatus,
        downloadedChapters = if (downloadStatus == DownloadStatus.Downloaded) 10L else 0L,
        totalChapters = 10L,
        localCoverUri = null,
        remoteCoverUrl = null,
        progressPercent = 0f
    )
}
