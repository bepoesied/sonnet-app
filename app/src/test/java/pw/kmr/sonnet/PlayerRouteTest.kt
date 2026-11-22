package pw.kmr.sonnet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pw.kmr.sonnet.player.PlayerScreen
import pw.kmr.sonnet.player.formatLabel
import pw.kmr.sonnet.player.formatSleepTimerCountdown
import pw.kmr.sonnet.shared.playback.PlayerChapter
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.playback.SleepTimerState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class PlayerRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeSliderScrubbingIsNotSnappedBackByLivePositionUpdates() {
        var uiState by mutableStateOf(
            PlayerUiState(
                bookId = "book-1",
                title = "Dune",
                author = "Frank Herbert",
                chapters = listOf(
                    PlayerChapter(
                        id = "ch-1",
                        title = "Chapter 1",
                        position = 0,
                        startPositionMs = 10_000L,
                        durationMs = 100_000L
                    )
                ),
                currentChapterId = "ch-1",
                currentChapterTitle = "Chapter 1",
                currentChapterStartPositionMs = 10_000L,
                currentChapterPositionMs = 10_000L,
                currentChapterDurationMs = 100_000L,
                canPlay = true
            )
        )
        var seekedPositionMs: Long? = null

        composeRule.setContent {
            MaterialTheme {
                PlayerScreen(
                    bookId = "book-1",
                    isDownloaded = true,
                    uiState = uiState,
                    onBack = {},
                    onPlayPause = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    onSeekTo = { seekedPositionMs = it },
                    onJumpToChapter = {},
                    onSetSleepTimer = {},
                    onUseRemoteProgress = {},
                    onKeepLocalProgress = {},
                    onClearError = {}
                )
            }
        }

        composeRule.onNodeWithText("0:10").assertIsDisplayed()

        // Begin dragging the seek slider toward ~80% of the chapter without lifting the finger.
        composeRule.onNodeWithContentDescription("Playback position").performTouchInput {
            val startX = left + width * 0.1f
            val endX = left + width * 0.8f
            down(Offset(startX, centerY))
            moveTo(Offset(startX + 40f, centerY))
            moveTo(Offset(endX, centerY))
        }
        composeRule.waitForIdle()

        assertNull("Seek should not be dispatched until drag finishes", seekedPositionMs)
        composeRule.onNodeWithText("0:10").assertDoesNotExist()

        // Simulate a 1 Hz playback tick while the user is still dragging.
        composeRule.runOnIdle {
            uiState = uiState.copy(currentChapterPositionMs = 11_000L)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("0:11").assertDoesNotExist()

        // Lift finger to commit the seek.
        composeRule.onNodeWithContentDescription("Playback position").performTouchInput {
            up()
        }
        composeRule.waitForIdle()

        assertNotNull("Seek should be dispatched once drag finishes", seekedPositionMs)
        assertTrue(
            "Expected seek target near 80% of chapter (+10s chapter start), got $seekedPositionMs",
            (seekedPositionMs ?: 0L) > 60_000L
        )
    }

    @Test
    fun sleepTimerCountdownFormattingAvoidsZeroAndSingularPluralMinutes() {
        assertEquals("0:01", formatSleepTimerCountdown(500L))
        assertEquals("0:45", formatSleepTimerCountdown(45_000L))
        assertEquals("1:00", formatSleepTimerCountdown(60_000L))
        assertEquals("14:59", formatSleepTimerCountdown(899_000L))
        assertEquals("1:00:00", formatSleepTimerCountdown(3_600_000L))
        assertEquals("0:45", SleepTimerState.Countdown(45_000L).formatLabel())
        assertEquals("1:00", SleepTimerState.Countdown(60_000L).formatLabel())

        var uiState by mutableStateOf(
            PlayerUiState(
                bookId = "book-1",
                title = "Dune",
                canPlay = true,
                sleepTimer = SleepTimerState.Countdown(45_000L)
            )
        )

        composeRule.setContent {
            MaterialTheme {
                PlayerScreen(
                    bookId = "book-1",
                    isDownloaded = true,
                    uiState = uiState,
                    onBack = {},
                    onPlayPause = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    onSeekTo = {},
                    onJumpToChapter = {},
                    onSetSleepTimer = {},
                    onUseRemoteProgress = {},
                    onKeepLocalProgress = {},
                    onClearError = {}
                )
            }
        }

        composeRule.onNodeWithText("0:45").assertIsDisplayed()
        composeRule.onNodeWithText("0 minutes").assertDoesNotExist()

        composeRule.runOnIdle {
            uiState = uiState.copy(sleepTimer = SleepTimerState.Countdown(60_000L))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1:00").assertIsDisplayed()
        composeRule.onNodeWithText("1 minutes").assertDoesNotExist()
    }

    @Test
    fun missingBookShowsUnavailableErrorUiWithBackAction() {
        var backClicks = 0

        composeRule.setContent {
            MaterialTheme {
                PlayerScreen(
                    bookId = "missing-book",
                    isDownloaded = true,
                    uiState = PlayerUiState(
                        bookMissing = true,
                        errorMessage = "This book is unavailable. Try downloading it again."
                    ),
                    onBack = { backClicks++ },
                    onPlayPause = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    onSeekTo = {},
                    onJumpToChapter = {},
                    onSetSleepTimer = {},
                    onUseRemoteProgress = {},
                    onKeepLocalProgress = {},
                    onClearError = {}
                )
            }
        }

        composeRule.onNodeWithText("This book is unavailable. Try downloading it again.").assertIsDisplayed()
        composeRule.onNodeWithText("Loading book").assertDoesNotExist()
        composeRule.onNodeWithText("Back to library").performClick()

        composeRule.runOnIdle {
            assertEquals(1, backClicks)
        }
    }
}
