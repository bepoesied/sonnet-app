package pw.kmr.sonnet.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pw.kmr.sonnet.R
import pw.kmr.sonnet.shared.auth.LoginEffect
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.LoginUiState
import pw.kmr.sonnet.shared.auth.LoginViewModel
import pw.kmr.sonnet.shared.auth.loginViewModelFactory

@Composable
fun LoginRoute(
    loginRepository: LoginRepository,
    onLoginCompleted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val factory = remember(loginRepository) {
        loginViewModelFactory(loginRepository)
    }
    val viewModel: LoginViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnLoginCompleted by rememberUpdatedState(onLoginCompleted)
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.completeLogin(AuthBrowserActivity.parseResult(result.resultCode, result.data))
    }

    LaunchedEffect(viewModel) {
        viewModel.loginEffects.collect { effect ->
            when (effect) {
                is LoginEffect.OpenAuthBrowser -> authLauncher.launch(
                    AuthBrowserActivity.createIntent(context, effect.authorizeUrl)
                )

                is LoginEffect.LoginCompleted -> currentOnLoginCompleted()
            }
        }
    }

    LoginScreen(
        uiState = uiState,
        onServerUrlChange = viewModel::onServerUrlChange,
        onStartLogin = viewModel::startLogin,
        modifier = modifier
    )
}

@Composable
internal fun LoginScreen(
    uiState: LoginUiState,
    onServerUrlChange: (String) -> Unit,
    onStartLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSubmit = !uiState.isLoading && uiState.serverUrl.isNotBlank()

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(R.string.login_subtitle),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                enabled = !uiState.isLoading,
                label = { Text(stringResource(R.string.login_server_url_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = { if (canSubmit) onStartLogin() }
                ),
                placeholder = { Text(stringResource(R.string.login_server_url_placeholder)) }
            )
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            val signingInDescription = stringResource(R.string.login_signing_in_cd)
            Button(
                onClick = onStartLogin,
                enabled = canSubmit,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = signingInDescription },
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                } else {
                    Text(stringResource(R.string.login_sign_in))
                }
            }
        }
    }
}
