package pw.kmr.sonnet

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
import pw.kmr.sonnet.shared.core.AppViewModel
import pw.kmr.sonnet.shared.core.appViewModelFactory
import pw.kmr.sonnet.ui.theme.SonnetTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels {
        val appContainer = (application as SonnetApplication).appContainer
        appViewModelFactory(appContainer.loginRepository, appContainer.authSessionManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appContainer = (application as SonnetApplication).appContainer
            val uiState by appViewModel.uiState.collectAsStateWithLifecycle()

            SonnetTheme {
                SonnetApp(
                    uiState = uiState,
                    appContainer = appContainer,
                    onLogout = {
                        appContainer.playbackController.shutdown()
                        appViewModel.logout()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
