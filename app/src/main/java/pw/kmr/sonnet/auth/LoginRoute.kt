package pw.kmr.sonnet.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import net.openid.appauth.AuthorizationService

@Composable
fun LoginRoute(
    authRepository: AuthRepository,
    modifier: Modifier = Modifier
) {
    val viewModel: LoginViewModel = viewModel(factory = loginViewModelFactory(authRepository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authorizationService = rememberAuthorizationService()
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.completeLogin(result.data, authorizationService)
    }

    LaunchedEffect(viewModel, authorizationService) {
        viewModel.loginEffects.collectLatest { effect ->
            when (effect) {
                is LoginEffect.OpenAuth -> authLauncher.launch(effect.intent)
            }
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Connect to Sonnet",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Enter your Sonnet server URL. The app will discover its mobile OIDC settings and sign in through your browser.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                enabled = !uiState.isLoading,
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                placeholder = { Text("https://sonnet.example.com") }
            )
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = { viewModel.startLogin(authorizationService) },
                enabled = !uiState.isLoading && uiState.serverUrl.isNotBlank(),
                modifier = Modifier.padding(top = 24.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}

@Composable
private fun rememberAuthorizationService(): AuthorizationService {
    val context = LocalContext.current
    val authorizationService = remember(context) { AuthorizationService(context) }
    DisposableEffect(authorizationService) {
        onDispose { authorizationService.dispose() }
    }
    return authorizationService
}
