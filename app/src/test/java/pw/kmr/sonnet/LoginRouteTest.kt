package pw.kmr.sonnet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pw.kmr.sonnet.auth.LoginScreen
import pw.kmr.sonnet.shared.auth.LoginUiState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun errorMessageIsRenderedWhenPresent() {
        composeRule.setContent {
            MaterialTheme {
                LoginScreen(
                    uiState = LoginUiState(
                        serverUrl = "https://sonnet.example.com",
                        isLoading = false,
                        errorMessage = "Cannot reach the server."
                    ),
                    onServerUrlChange = {},
                    onStartLogin = {}
                )
            }
        }

        composeRule.onNodeWithText("Cannot reach the server.").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun submittingStateShowsProgressIndicatorAndDisablesInput() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                LoginScreen(
                    uiState = LoginUiState(
                        serverUrl = "https://sonnet.example.com",
                        isLoading = true,
                        errorMessage = null
                    ),
                    onServerUrlChange = {},
                    onStartLogin = {}
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithContentDescription("Signing in").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertDoesNotExist()
        composeRule.onNodeWithText("https://sonnet.example.com").assertIsNotEnabled()
    }
}
