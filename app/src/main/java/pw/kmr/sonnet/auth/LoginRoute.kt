package pw.kmr.sonnet.auth

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import pw.kmr.sonnet.shared.auth.LoginEffect
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.LoginViewModel
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.auth.loginViewModelFactory

@Composable
fun LoginRoute(
    loginRepository: LoginRepository,
    platformAuthProvider: PlatformAuthProvider,
    modifier: Modifier = Modifier
) {
    val viewModel: LoginViewModel = viewModel(
        factory = loginViewModelFactory(loginRepository, platformAuthProvider)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.let { viewModel.completeLogin(it) }
    }

    LaunchedEffect(viewModel) {
        viewModel.loginEffects.collectLatest { effect ->
            when (effect) {
                is LoginEffect.OpenAuthBrowser -> authLauncher.launch(effect.authData as Intent)
                is LoginEffect.LoginCompleted -> {}
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
                onClick = viewModel::startLogin,
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
