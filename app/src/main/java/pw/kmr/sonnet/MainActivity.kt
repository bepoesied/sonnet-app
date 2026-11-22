package pw.kmr.sonnet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pw.kmr.sonnet.core.AppContainer
import pw.kmr.sonnet.core.ui.SonnetApp
import pw.kmr.sonnet.player.SonnetMediaSessionService
import pw.kmr.sonnet.shared.core.AppUiState
import pw.kmr.sonnet.shared.core.AppViewModel
import pw.kmr.sonnet.shared.core.appViewModelFactory
import pw.kmr.sonnet.ui.theme.SonnetTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels {
        val appContainer = (application as SonnetApplication).appContainer
        appViewModelFactory(appContainer.loginRepository, appContainer.authSessionManager)
    }

    private var launchIntentConsumed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            appViewModel.uiState.value is AppUiState.Loading
        }
        enableEdgeToEdge()

        launchIntentConsumed = savedInstanceState?.getBoolean(KEY_LAUNCH_INTENT_CONSUMED, false) ?: false

        val appContainer = (application as SonnetApplication).appContainer
        if (!launchIntentConsumed) {
            handlePlayerIntent(intent, appContainer)
        }

        setContent {
            val uiState by appViewModel.uiState.collectAsStateWithLifecycle()

            SonnetTheme {
                SonnetApp(
                    uiState = uiState,
                    appContainer = appContainer,
                    onLogout = {
                        appContainer.stopPlaybackIfInitialized()
                        appViewModel.logout()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCH_INTENT_CONSUMED, launchIntentConsumed)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val appContainer = (application as SonnetApplication).appContainer
        handlePlayerIntent(intent, appContainer)
    }

    private fun handlePlayerIntent(intent: Intent?, appContainer: AppContainer) {
        if (intent == null) return
        if (intent.action != SonnetMediaSessionService.ACTION_OPEN_PLAYER) return
        if (intent.getBooleanExtra(EXTRA_PLAYER_INTENT_CONSUMED, false)) return

        val bookId = intent.getStringExtra(SonnetMediaSessionService.EXTRA_BOOK_ID)
            ?.takeIf { it.isNotBlank() }

        intent.putExtra(EXTRA_PLAYER_INTENT_CONSUMED, true)
        intent.removeExtra(SonnetMediaSessionService.EXTRA_BOOK_ID)
        intent.action = null
        setIntent(intent)
        launchIntentConsumed = true

        if (bookId != null) {
            appContainer.playbackOrchestrator.openFullPlayer(bookId)
        } else {
            appContainer.playbackOrchestrator.requestOpenCurrentPlayer()
        }
    }

    private companion object {
        const val KEY_LAUNCH_INTENT_CONSUMED = "launch_intent_consumed"
        const val EXTRA_PLAYER_INTENT_CONSUMED = "pw.kmr.sonnet.extra.PLAYER_INTENT_CONSUMED"
    }
}
