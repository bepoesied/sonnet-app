package pw.kmr.sonnet.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneDecoratorStrategyScope
import pw.kmr.sonnet.player.PlayerUiState

class MiniPlayerDecoratorStrategy<T : Any>(
    private val playerState: PlayerUiState,
    private val onTapMiniPlayer: () -> Unit,
    private val onPlayPause: () -> Unit
) : SceneDecoratorStrategy<T> {

    override fun SceneDecoratorStrategyScope<T>.decorateScene(scene: Scene<T>): Scene<T> {
        val isLogin = scene.entries.any { it.metadata[METADATA_IS_LOGIN] == true }
        val isFullPlayer = scene.entries.any { it.metadata[METADATA_IS_FULL_PLAYER] == true }
        if (isLogin || isFullPlayer || playerState.bookId == null) return scene

        return MiniPlayerDecoratedScene(scene, playerState, onTapMiniPlayer, onPlayPause)
    }

    companion object {
        private const val METADATA_IS_LOGIN = "MiniPlayerDecorator.isLogin"
        private const val METADATA_IS_FULL_PLAYER = "MiniPlayerDecorator.isFullPlayer"

        fun loginSceneMetadata(): Map<String, Any> = mapOf(METADATA_IS_LOGIN to true)
        fun fullPlayerMetadata(): Map<String, Any> = mapOf(METADATA_IS_FULL_PLAYER to true)
    }
}

private class MiniPlayerDecoratedScene<T : Any>(
    private val inner: Scene<T>,
    private val playerState: PlayerUiState,
    private val onTapMiniPlayer: () -> Unit,
    private val onPlayPause: () -> Unit
) : Scene<T> {
    override val key = inner::class to inner.key
    override val entries = inner.entries
    override val previousEntries = inner.previousEntries
    override val metadata = inner.metadata

    override val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = MINI_PLAYER_HEIGHT)
            ) {
                inner.content()
            }
            MiniPlayer(
                uiState = playerState,
                onClick = onTapMiniPlayer,
                onPlayPause = onPlayPause,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    companion object {
        private val MINI_PLAYER_HEIGHT = 104.dp
    }
}
