    val isAuthenticated = when (uiState) {
        AppUiState.Initial -> true
        is AppUiState.Ready -> uiState.session != null

    AppNavHost(
        isAuthenticated = isAuthenticated,
        appContainer = appContainer,
        onLogout = onLogout,
        modifier = modifier
    )
                            ),
                            progressModifier = Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState(key = "player-progress"),
                                animatedVisibilityScope = this@AnimatedContent
                            ),
                            surfaceBoundsModifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "player-surface"),
                                animatedVisibilityScope = this@AnimatedContent,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.fillMaxSize()
    playButtonModifier: Modifier = Modifier,
    progressModifier: Modifier = Modifier,
    surfaceBoundsModifier: Modifier = Modifier,
            .then(surfaceBoundsModifier)
            HorizontalDivider(modifier = progressModifier.height(3.dp))
                    enabled = uiState.canPlay && !uiState.isCheckingRemoteState,
                    modifier = playButtonModifier
import androidx.compose.animation.SharedTransitionLayout
@OptIn(ExperimentalSharedTransitionApi::class)
    val overlay = playerOverlay
    val playerSurface = when {
        overlay != null -> PlayerSurface.Full(overlay)
        showMiniPlayer -> PlayerSurface.Mini(playerState.bookId.orEmpty())
        else -> PlayerSurface.None
    }
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showMiniPlayer) 104.dp else 0.dp)
            ) {
                composable(AppDestination.Login.route) {
                    LoginRoute(authRepository = appContainer.authRepository)
                }
                composable(AppDestination.Library.route) {
                    LibraryRoute(
                        repository = appContainer.libraryRepository,
                        onOpenPlayer = { book -> playerOverlay = PlayerOverlayRequest(book.id, book.isDownloaded) },
                        onLogout = onLogout
                    )
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
                                coverModifier = Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState(key = "player-cover-${surface.bookId}"),
                                    animatedVisibilityScope = this@AnimatedContent
                                ),
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    is PlayerSurface.Full -> {
                        PlayerRoute(
                            bookId = surface.request.bookId,
                            isDownloaded = surface.request.isDownloaded,
                            playbackController = appContainer.playbackController,
                            onBack = { playerOverlay = null },
                            coverModifier = Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState(key = "player-cover-${surface.request.bookId}"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                        )
                    }
                }
            }
private sealed interface PlayerSurface {
    data object None : PlayerSurface
    data class Mini(val bookId: String) : PlayerSurface
    data class Full(val request: PlayerOverlayRequest) : PlayerSurface
}

    coverModifier: Modifier = Modifier,
                MiniPlayerCover(uiState = uiState, modifier = coverModifier)
                .padding(bottom = if (showMiniPlayer) 104.dp else 0.dp)
        ) {
            composable(AppDestination.Login.route) {
                LoginRoute(authRepository = appContainer.authRepository)
            }
            composable(
                route = AppDestination.Library.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                LibraryRoute(
                    repository = appContainer.libraryRepository,
                    onOpenPlayer = { book -> navController.navigate(AppDestination.Player.route(book.id, book.isDownloaded)) },
                    onLogout = onLogout
                )
            }
            composable(
                route = AppDestination.Player.route,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("downloaded") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                ),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) { backStackEntry ->
                PlayerRoute(
                    bookId = backStackEntry.arguments?.getString("bookId").orEmpty(),
                    isDownloaded = backStackEntry.arguments?.getBoolean("downloaded") == true,
                    playbackController = appContainer.playbackController,
                    onBack = { navController.popBackStack() }
                )
            }

        if (showMiniPlayer) {
            MiniPlayer(
                uiState = playerState,
                onClick = {
                    playerState.bookId?.let { bookId ->
                        navController.navigate(AppDestination.Player.route(bookId, downloaded = true)) {
                            launchSingleTop = true
                        }
                    }
                },
                onPlayPause = appContainer.playbackController::playPause,
                modifier = Modifier.align(Alignment.BottomCenter)
    }
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
                    (uiState.positionMs.toFloat() / uiState.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
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
private fun MiniPlayerCover(uiState: PlayerUiState, modifier: Modifier = Modifier) {
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
            Text(
                text = uiState.title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    LaunchedEffect(isAuthenticated) {
        val destination = if (isAuthenticated) AppDestination.Library.route else AppDestination.Login.route
        navController.navigate(destination) {
            popUpTo(0)
            launchSingleTop = true
        }
    }

            LoginRoute(authRepository = appContainer.authRepository)
                onOpenPlayer = { navController.navigate(AppDestination.Player.route) },
                onLogout = onLogout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pw.kmr.sonnet.auth.LoginRoute
import pw.kmr.sonnet.library.LibraryRoute
import pw.kmr.sonnet.player.PlayerRoute

@Composable
fun SonnetApp(
    uiState: AppUiState,
    modifier: Modifier = Modifier
) {
    when (uiState) {
        AppUiState.Loading -> LoadingApp(modifier = modifier)
        is AppUiState.Ready -> AppNavHost(
            isAuthenticated = uiState.session != null,
            modifier = modifier
        )
    }
}

@Composable
private fun AppNavHost(
    isAuthenticated: Boolean,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val startDestination = if (isAuthenticated) {
        AppDestination.Library.route
    } else {
        AppDestination.Login.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(AppDestination.Login.route) {
            LoginRoute()
        }
        composable(AppDestination.Library.route) {
            LibraryRoute(
                onOpenPlayer = { navController.navigate(AppDestination.Player.route) }
            )
        }
        composable(AppDestination.Player.route) {
            PlayerRoute(onBack = { navController.popBackStack() })
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