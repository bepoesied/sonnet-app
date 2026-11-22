package pw.kmr.sonnet.core.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pw.kmr.sonnet.auth.LoginRoute
import pw.kmr.sonnet.core.AppContainer
import pw.kmr.sonnet.library.LibraryRoute
import pw.kmr.sonnet.player.PlayerRoute
import pw.kmr.sonnet.shared.core.AppUiState

@Composable
fun SonnetApp(
    uiState: AppUiState,
    appContainer: AppContainer,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSync = uiState is AppUiState.Ready && uiState.session != null && !uiState.requiresReauth
    LaunchedEffect(canSync) {
        if (canSync) {
            appContainer.syncCoordinator.start()
        } else {
            appContainer.stopSyncCoordinatorIfInitialized()
        }
    }

    when (uiState) {
        AppUiState.Loading -> LoadingApp(modifier = modifier)
        is AppUiState.Ready -> AppNavHost(
            isAuthenticated = uiState.session != null,
            requiresReauth = uiState.requiresReauth,
            appContainer = appContainer,
            onLogout = onLogout,
            modifier = modifier
        )
    }
}

@Composable
private fun AppNavHost(
    isAuthenticated: Boolean,
    requiresReauth: Boolean,
    appContainer: AppContainer,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startKey = if (isAuthenticated) LibraryKey else LoginKey
    val backStack = rememberNavBackStack(startKey)
    var wasAuthenticated by remember { mutableStateOf(isAuthenticated) }

    LaunchedEffect(isAuthenticated, requiresReauth) {
        if (!isAuthenticated) {
            if (backStack.size != 1 || backStack.lastOrNull() != LoginKey) {
                backStack.clear()
                backStack.add(LoginKey)
            }
            if (wasAuthenticated) {
                appContainer.stopPlaybackIfInitialized()
            }
        } else if (!requiresReauth) {
            if (backStack.size != 1 || backStack.lastOrNull() != LibraryKey) {
                backStack.clear()
                backStack.add(LibraryKey)
            }
        } else if (backStack.size == 1 && backStack.lastOrNull() == LoginKey) {
            backStack.clear()
            backStack.add(LibraryKey)
        }
        wasAuthenticated = isAuthenticated
    }

    val provider = entryProvider {
        entry<LoginKey> {
            LoginRoute(
                loginRepository = appContainer.loginRepository,
                platformAuthProvider = appContainer.platformAuthProvider,
                onLoginCompleted = {
                    if (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    }
                }
            )
        }
        entry<LibraryKey> {
            if (isAuthenticated) {
                LibraryWithPlayer(
                    appContainer = appContainer,
                    onLogout = onLogout,
                    onReauthenticate = {
                        if (backStack.lastOrNull() != LoginKey) {
                            backStack.add(LoginKey)
                        }
                    }
                )
            }
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = provider
    )

    // Auth has one root entry (with optional stacked LoginKey for in-place re-auth). The player
    // is not a Nav3 destination: keeping the library composed underneath it prevents a second back
    // owner and any predictive-pop scene seam.
    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

private data class PlayerDestination(val bookId: String, val isDownloaded: Boolean)

private val playerDestinationSaver = listSaver<PlayerDestination?, Any>(
    save = { destination ->
        if (destination == null) emptyList() else listOf(destination.bookId, destination.isDownloaded)
    },
    restore = { saved ->
        if (saved.isEmpty()) null else PlayerDestination(saved[0] as String, saved[1] as Boolean)
    }
)

@Composable
private fun LibraryWithPlayer(
    appContainer: AppContainer,
    onLogout: () -> Unit,
    onReauthenticate: () -> Unit
) {
    // Observe only whether a book is active so 1 Hz playback ticks never recompose LibraryWithPlayer
    // or LibraryRoute.
    val hasActiveBookFlow = remember(appContainer.playbackOrchestrator) {
        appContainer.playbackOrchestrator.state
            .map { it.bookId != null }
            .distinctUntilChanged()
    }
    val hasActiveBook by hasActiveBookFlow.collectAsStateWithLifecycle(initialValue = false)

    var playerDestination by rememberSaveable(stateSaver = playerDestinationSaver) {
        mutableStateOf<PlayerDestination?>(null)
    }
    var dialogVisible by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    var closeJob by remember { mutableStateOf<Job?>(null) }
    val expansion = remember { Animatable(if (playerDestination != null) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }

    fun openPlayer(destination: PlayerDestination) {
        val current = playerDestination
        if (current == destination && !isClosing) return
        closeJob?.cancel()
        closeJob = null
        isClosing = false
        focusManager.clearFocus()
        keyboardController?.hide()
        dialogVisible = false
        playerDestination = destination
    }

    fun closePlayer() {
        if (playerDestination == null || isClosing) return
        isClosing = true
        closeJob?.cancel()
        closeJob = scope.launch {
            expansion.animateTo(0f, animationSpec = tween(300))
            playerDestination = null
            isClosing = false
            closeJob = null
        }
    }

    LaunchedEffect(playerDestination, isClosing) {
        if (playerDestination != null && !isClosing && expansion.value < 1f) {
            expansion.animateTo(1f, animationSpec = tween(360))
        }
    }

    // This request is retained by the orchestrator during login/session hydration, then consumed
    // when the authenticated library is mounted. No request can place a player over the login UI.
    LaunchedEffect(Unit) {
        appContainer.playbackOrchestrator.pendingPlayerRequest.collect { request ->
            if (request != null) {
                appContainer.playbackOrchestrator.consumePendingPlayerRequest()
                openPlayer(PlayerDestination(request, isDownloaded = true))
            }
        }
    }

    PredictiveBackHandler(enabled = playerDestination != null && !dialogVisible && !isClosing) { progress ->
        if (isClosing || playerDestination == null) return@PredictiveBackHandler
        var completed = false
        val startingExpansion = expansion.value
        try {
            progress.collect { event ->
                expansion.snapTo(startingExpansion * (1f - event.progress.coerceIn(0f, 1f)))
            }
            completed = true
            isClosing = true
            expansion.animateTo(0f, animationSpec = tween(220))
            playerDestination = null
            isClosing = false
        } catch (_: CancellationException) {
            if (!completed) {
                // The gesture coroutine is cancelled on abort; restore in the composition scope.
                scope.launch { expansion.animateTo(1f, animationSpec = tween(220)) }
            }
        }
    }

    // Scaffold measures the bottom bar before the body, so library padding is always its exact
    // height (including navigation insets). It also draws the mini-player above the sliding sheet:
    // fading it in on collapse does not reveal it abruptly after the sheet is removed.
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerHeightPx = it.height },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (hasActiveBook) {
                MiniPlayerHost(
                    playbackOrchestrator = appContainer.playbackOrchestrator,
                    onOpenPlayer = { bookId ->
                        openPlayer(PlayerDestination(bookId, isDownloaded = true))
                    },
                    enabled = playerDestination == null,
                    modifier = Modifier
                        .onSizeChanged { miniPlayerHeightPx = it.height }
                        .graphicsLayer { alpha = (1f - expansion.value * 5f).coerceIn(0f, 1f) }
                        .then(if (playerDestination != null) Modifier.clearAndSetSemantics {} else Modifier)
                )
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LibraryRoute(
                repository = appContainer.libraryRepository,
                onOpenPlayer = { book -> openPlayer(PlayerDestination(book.id, book.isDownloaded)) },
                onLogout = onLogout,
                onReauthenticate = onReauthenticate,
                requiresReauth = appContainer.authSessionManager.requiresLogin,
                isOnline = appContainer.networkMonitor.isOnline,
                downloadScheduler = appContainer.bookDownloadScheduler,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = contentPadding.calculateBottomPadding())
                    .consumeWindowInsets(contentPadding)
                    .then(if (playerDestination != null) Modifier.clearAndSetSemantics {} else Modifier)
            )

            val destination = playerDestination
            if (destination != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(Color.Black.copy(alpha = expansion.value * 0.12f))
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                )
                val barHeightPx = if (hasActiveBook) miniPlayerHeightPx else 0
                val sheetTravelPx = (containerHeightPx - barHeightPx).coerceAtLeast(0)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = sheetTravelPx * (1f - expansion.value)
                        }
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    PlayerRoute(
                        bookId = destination.bookId,
                        isDownloaded = destination.isDownloaded,
                        playbackOrchestrator = appContainer.playbackOrchestrator,
                        onBack = ::closePlayer,
                        onDialogVisibilityChange = { dialogVisible = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingApp(modifier: Modifier = Modifier) {
    // Session hydration only reads encrypted local storage. Keep this transitional frame visually
    // neutral while that work happens on IO rather than showing a misleading network loader.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    )
}
