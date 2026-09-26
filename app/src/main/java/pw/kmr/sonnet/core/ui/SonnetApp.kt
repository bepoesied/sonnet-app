package pw.kmr.sonnet.core.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CancellationException
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
    when (uiState) {
        AppUiState.Loading -> LoadingApp(modifier = modifier)
        is AppUiState.Ready -> AppNavHost(
            isAuthenticated = uiState.session != null,
            appContainer = appContainer,
            onLogout = onLogout,
            modifier = modifier
        )
    }
}

@Composable
private fun AppNavHost(
    isAuthenticated: Boolean,
    appContainer: AppContainer,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startKey = if (isAuthenticated) LibraryKey else LoginKey
    val backStack = rememberNavBackStack(startKey)

    LaunchedEffect(isAuthenticated) {
        val targetKey = if (isAuthenticated) LibraryKey else LoginKey
        if (backStack.lastOrNull() != targetKey) {
            backStack.clear()
            backStack.add(targetKey)
        }
        if (!isAuthenticated) {
            appContainer.playbackOrchestrator.shutdown()
        }
    }

    val provider = entryProvider {
        entry<LoginKey> {
            LoginRoute(
                loginRepository = appContainer.loginRepository,
                platformAuthProvider = appContainer.platformAuthProvider
            )
        }
        entry<LibraryKey> {
            if (isAuthenticated) {
                LibraryWithPlayer(appContainer = appContainer, onLogout = onLogout)
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

    // Auth has exactly one root entry. The player is not a Nav3 destination: keeping the library
    // composed underneath it prevents a second back owner and any predictive-pop scene seam.
    NavDisplay(
        entries = entries,
        onBack = { backStack.removeLastOrNull() },
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
private fun LibraryWithPlayer(appContainer: AppContainer, onLogout: () -> Unit) {
    val playerState by appContainer.playbackOrchestrator.state.collectAsStateWithLifecycle()
    var playerDestination by rememberSaveable(stateSaver = playerDestinationSaver) {
        mutableStateOf<PlayerDestination?>(null)
    }
    var dialogVisible by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    val expansion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }

    fun openPlayer(destination: PlayerDestination) {
        if (playerDestination != null) return
        focusManager.clearFocus()
        keyboardController?.hide()
        dialogVisible = false
        playerDestination = destination
    }

    fun closePlayer() {
        if (playerDestination == null || isClosing) return
        isClosing = true
        scope.launch {
            expansion.animateTo(0f, animationSpec = tween(300))
            playerDestination = null
            isClosing = false
        }
    }

    LaunchedEffect(playerDestination) {
        if (playerDestination != null) expansion.animateTo(1f, animationSpec = tween(360))
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
            if (playerState.bookId != null) {
                MiniPlayer(
                    uiState = playerState,
                    onClick = {
                        playerState.bookId?.let { openPlayer(PlayerDestination(it, isDownloaded = true)) }
                    },
                    onPlayPause = appContainer.playbackOrchestrator::playPause,
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
                        .background(Color.Black.copy(alpha = expansion.value * 0.12f))
                        .clickable(onClick = {})
                )
                val barHeightPx = if (playerState.bookId != null) miniPlayerHeightPx else 0
                val sheetTravelPx = (containerHeightPx - barHeightPx).coerceAtLeast(0)
                val playerViewModelStore = remember { ViewModelStore() }
                DisposableEffect(playerViewModelStore) {
                    onDispose { playerViewModelStore.clear() }
                }
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides remember(playerViewModelStore) {
                        object : ViewModelStoreOwner {
                            override val viewModelStore = playerViewModelStore
                        }
                    }
                ) {
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
