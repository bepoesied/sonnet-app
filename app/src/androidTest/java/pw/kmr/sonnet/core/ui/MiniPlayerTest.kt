package pw.kmr.sonnet.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import pw.kmr.sonnet.shared.playback.PlayerUiState

class MiniPlayerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun disabledMiniPlayerDoesNotBlockExpandedPlayerTouches() {
        var playerClicks = 0
        var miniClicks = 0
        var playPauseClicks = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(140.dp)) {
                    Box(Modifier.fillMaxSize().clickable { playerClicks++ }.testTag("expandedPlayer"))
                    MiniPlayer(
                        uiState = PlayerUiState(bookId = "book", title = "Book", canPlay = true),
                        onClick = { miniClicks++ },
                        onPlayPause = { playPauseClicks++ },
                        enabled = false
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Play").assertIsNotEnabled()
        composeRule.onNodeWithTag("expandedPlayer").performTouchInput { click(center) }
        composeRule.runOnIdle {
            assertEquals(1, playerClicks)
            assertEquals(0, miniClicks)
            assertEquals(0, playPauseClicks)
        }
    }
}
