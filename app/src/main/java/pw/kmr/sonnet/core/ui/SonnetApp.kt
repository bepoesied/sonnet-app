package pw.kmr.sonnet.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import java.util.Locale
import pw.kmr.sonnet.auth.LoginRoute
import pw.kmr.sonnet.core.AppContainer
import pw.kmr.sonnet.library.LibraryRoute
import pw.kmr.sonnet.player.PlayerRoute
import pw.kmr.sonnet.player.PlayerUiState
import pw.kmr.sonnet.shared.core.AppUiState
import pw.kmr.sonnet.shared.model.LibraryBook

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
    val navController = rememberNavController()
    val playerState by appContainer.playbackController.state.collectAsStateWithLifecycle()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var playerOverlay by remember { mutableStateOf<PlayerOverlayRequest?>(null) }

    val showPlayerOverlay = playerOverlay != null
    val showMiniPlayer = playerState.bookId != null && currentRoute == AppDestination.Library.route && !showPlayerOverlay
    val playerSurface = when {
        playerOverlay != null -> PlayerSurface.Full(playerOverlay!!)
        showMiniPlayer -> PlayerSurface.Mini(playerState.bookId.orEmpty())
        else -> PlayerSurface.None
    }

    val startDestination = if (isAuthenticated) AppDestination.Library.route else AppDestination.Login.route

    LaunchedEffect(isAuthenticated) {
        val destination = if (isAuthenticated) AppDestination.Library.route else AppDestination.Login.route
        navController.navigate(destination) {
            popUpTo(0)
            launchSingleTop = true
        }
        if (!isAuthenticated) {
            playerOverlay = null
        }
    }

    BackHandler(enabled = showPlayerOverlay) {
        playerOverlay = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (showMiniPlayer) 104.dp else 0.dp)
        ) {
            composable(AppDestination.Login.route) {
                LoginRoute(
                    loginRepository = appContainer.loginRepository,
                    platformAuthProvider = appContainer.platformAuthProvider
                )
            }
            composable(AppDestination.Library.route) {
                LibraryRoute(
                    repository = appContainer.libraryRepository,
                    onOpenPlayer = { book -> playerOverlay = PlayerOverlayRequest(book.id, book.isDownloaded) },
                    onLogout = onLogout
                )
            }
        }

        AnimatedContent(
            targetState = playerSurface,
            label = "player-surface",
            modifier = Modifier.fillMaxSize()
        ) { surface ->
            when (surface) {
                PlayerSurface.None -> Unit
                is PlayerSurface.Mini -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MiniPlayer(
                            uiState = playerState,
                            onClick = {
                                playerOverlay = PlayerOverlayRequest(bookId = surface.bookId, isDownloaded = true)
                            },
                            onPlayPause = appContainer.playbackController::playPause,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
                is PlayerSurface.Full -> {
                    PlayerRoute(
                        bookId = surface.request.bookId,
                        isDownloaded = surface.request.isDownloaded,
                        playbackController = appContainer.playbackController,
                        onBack = { playerOverlay = null },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private data class PlayerOverlayRequest(
    val bookId: String,
    val isDownloaded: Boolean
)

private sealed interface PlayerSurface {
    data object None : PlayerSurface

    data class Mini(val bookId: String) : PlayerSurface

    data class Full(val request: PlayerOverlayRequest) : PlayerSurface
}

@Composable
private fun MiniPlayer(
    uiState: PlayerUiState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column {
            LinearProgressIndicator(
                progress = {
                    (uiState.currentChapterPositionMs.toFloat() /
                        uiState.currentChapterDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniPlayerCover(uiState = uiState)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.title.ifBlank { "Loading book" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.currentChapterTitle.ifBlank { uiState.author ?: "Preparing chapters" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    enabled = uiState.canPlay && !uiState.isCheckingRemoteState
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play"
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MiniPlayerCover(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val coverFilePath = uiState.coverFilePath
        if (coverFilePath == null) {
            val letter = uiState.title.firstOrNull()?.toString()?.uppercase(Locale.ROOT) ?: "?"
            Text(
                text = letter,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(coverFilePath))
                    .memoryCacheKey("mini-player-cover-$coverFilePath")
                    .diskCacheKey("mini-player-cover-$coverFilePath")
                    .build(),
                contentDescription = "${uiState.title} cover",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LoadingApp(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
