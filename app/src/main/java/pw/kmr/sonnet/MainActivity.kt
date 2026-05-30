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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pw.kmr.sonnet.core.ui.SonnetApp
import pw.kmr.sonnet.player.SonnetMediaSessionService
import pw.kmr.sonnet.shared.core.AppViewModel
import pw.kmr.sonnet.shared.core.appViewModelFactory
import pw.kmr.sonnet.ui.theme.SonnetTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels {
        val appContainer = (application as SonnetApplication).appContainer
        appViewModelFactory(appContainer.loginRepository, appContainer.authSessionManager)
    }
    private var lastHandledIntentHashCode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as SonnetApplication).appContainer
        handlePlayerIntent(intent, appContainer)

        setContent {
            val uiState by appViewModel.uiState.collectAsStateWithLifecycle()

            SonnetTheme {
                SonnetApp(
                    uiState = uiState,
                    appContainer = appContainer,
                    onLogout = {
                        appContainer.playbackOrchestrator.shutdown()
                        appViewModel.logout()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val appContainer = (application as SonnetApplication).appContainer
        handlePlayerIntent(intent, appContainer)
    }

    private fun handlePlayerIntent(intent: Intent, appContainer: pw.kmr.sonnet.core.AppContainer) {
        if (intent.action == SonnetMediaSessionService.ACTION_OPEN_PLAYER && intent.hashCode() != lastHandledIntentHashCode) {
            lastHandledIntentHashCode = intent.hashCode()
            val bookId = appContainer.playbackOrchestrator.state.value.bookId
            if (bookId != null) {
                appContainer.playbackOrchestrator.openFullPlayer(bookId)
            }
        }
    }
}
