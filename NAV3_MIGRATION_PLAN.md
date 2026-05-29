# Navigation 3 Migration Plan

## Overview

Migrate from `androidx.navigation:navigation-compose` (string-based routes) to `androidx.navigation3` with typed NavKey classes, a scene decorator for the mini player, and the full player as a dialog overlay scene. Predictive back enabled everywhere.

---

## Phase 1: Dependencies & Build Config

### `gradle/libs.versions.toml`

Add versions:

```toml
nav3Core = "1.1.2"
lifecycleViewmodelNav3 = "2.11.0-beta02"
material3AdaptiveNav3 = "1.3.0-beta02"
```

Add libraries:

```toml
# Core Navigation 3 libraries
androidx-navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "nav3Core" }
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "nav3Core" }

# Add-on libraries
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycleViewmodelNav3" }
androidx-material3-adaptive-navigation3 = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation3", version.ref = "material3AdaptiveNav3" }
```

Note: `kotlinx-serialization-json` already exists (line 71). `kotlin-serialization` plugin already defined (line 88).

### `app/build.gradle.kts`

- Apply `kotlin-serialization` plugin (add `alias(libs.plugins.kotlin.serialization)` to plugins block)
- Add dependencies:
  ```kotlin
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.material3.adaptive.navigation3)
  ```
- Remove: `implementation(libs.androidx.navigation.compose)`

No compileSdk change needed — already at 36.

---

## Phase 2: Define Navigation Keys

### New file: `app/src/main/java/pw/kmr/sonnet/core/ui/NavKeys.kt`

```kotlin
package pw.kmr.sonnet.core.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object LoginKey : NavKey

@Serializable
data object LibraryKey : NavKey

@Serializable
data class FullPlayerKey(val bookId: String, val isDownloaded: Boolean) : NavKey
```

### Delete: `app/src/main/java/pw/kmr/sonnet/core/ui/AppDestination.kt`

Replaced entirely by NavKeys.

---

## Phase 3: Back Stack & Auth Routing

In `SonnetApp.kt`, replace `NavController` with Nav3 back stack:

```kotlin
val startKey = if (isAuthenticated) LibraryKey else LoginKey
val backStack = rememberNavBackStack(startKey)
```

**Auth routing (no flicker):**
- If `isAuthenticated` (from `AppUiState.Ready`), start at `LibraryKey` directly
- If not authenticated, start at `LoginKey`
- On logout: `backStack.clear(); backStack.add(LoginKey)`
- On successful login: `backStack.clear(); backStack.add(LibraryKey)`
- Background auth validation: `LaunchedEffect(isAuthenticated)` — if tokens expire, navigate to `LoginKey` (same pattern as today but using back stack ops)

Remove: `navController`, `rememberNavController()`, `currentBackStackEntryAsState()`, `NavHost`, `composable` blocks.

---

## Phase 4: Entry Provider

Replace NavHost composable blocks with entryProvider:

```kotlin
val entryProvider = entryProvider {
    entry<LoginKey> {
        LoginRoute(
            loginRepository = appContainer.loginRepository,
            platformAuthProvider = appContainer.platformAuthProvider
        )
    }
    entry<LibraryKey> {
        LibraryRoute(
            repository = appContainer.libraryRepository,
            onOpenPlayer = { book -> backStack.add(FullPlayerKey(book.id, book.isDownloaded)) },
            onLogout = onLogout
        )
    }
    entry<FullPlayerKey>(
        metadata = metadata {
            put(NavDisplay.DialogKey, true)
        }
    ) { key ->
        PlayerRoute(
            bookId = key.bookId,
            isDownloaded = key.isDownloaded,
            playbackController = appContainer.playbackController,
            onBack = { backStack.removeLastOrNull() }
        )
    }
}
```

Key change: `onOpenPlayer` now pushes `FullPlayerKey` onto back stack instead of setting local state.

---

## Phase 5: Mini Player Scene Decorator

### New file: `app/src/main/java/pw/kmr/sonnet/core/ui/MiniPlayerDecoratorStrategy.kt`

A `SceneDecoratorStrategy` that wraps non-login scenes with the mini player bar at the bottom.

```kotlin
package pw.kmr.sonnet.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.Scene
import androidx.navigation3.ui.SceneDecoratorStrategy
import androidx.navigation3.ui.SceneDecoratorStrategyScope
import pw.kmr.sonnet.player.PlayerUiState

class MiniPlayerDecoratorStrategy<T : Any>(
    private val playerState: PlayerUiState,
    private val onTapMiniPlayer: () -> Unit,
    private val onPlayPause: () -> Unit
) : SceneDecoratorStrategy<T> {

    override fun SceneDecoratorStrategyScope<T>.decorateScene(scene: Scene<T>): Scene<T> {
        // Don't show mini player on login screen
        val isLogin = scene.entries.any { it.key is LoginKey }
        if (isLogin || playerState.bookId == null) return scene

        return MiniPlayerDecoratedScene(scene, playerState, onTapMiniPlayer, onPlayPause)
    }
}

class MiniPlayerDecoratedScene<T : Any>(
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
                    .padding(bottom = 104.dp)
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
}
```

**Mini player visibility**: Shows on all non-login scenes automatically. Scales to future screens without code changes.

Note: `MiniPlayer` composable needs to be extracted from `SonnetApp.kt` into its own file or made accessible. Currently it's `private fun MiniPlayer(...)` in SonnetApp.kt — make it internal or move to `MiniPlayerDecoratorStrategy.kt`.

---

## Phase 6: NavDisplay Assembly

```kotlin
val playerState by appContainer.playbackController.state.collectAsStateWithLifecycle()

val entryProvider = entryProvider { /* ... from Phase 4 ... */ }

NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    sceneStrategies = listOf(DialogSceneStrategy()),
    sceneDecoratorStrategies = listOf(
        MiniPlayerDecoratorStrategy(
            playerState = playerState,
            onTapMiniPlayer = {
                // Find current book info and open full player
                backStack.add(FullPlayerKey(playerState.bookId!!, true))
            },
            onPlayPause = appContainer.playbackController::playPause
        )
    ),
    entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator()
    ),
    entryProvider = entryProvider
)
```

---

## Phase 7: Predictive Back & Animations

### Predictive back
- Nav3 handles predictive back automatically for entries on the back stack
- Full player dialog: swipe back → pops `FullPlayerKey` → returns to mini player state
- Login/Library: swipe back → standard back behavior

### Transitions (optional, can add later)
```kotlin
NavDisplay(
    // ...
    transitionSpec = {
        slideInHorizontally(initialOffsetX = { it }) togetherWith
            slideOutHorizontally(targetOffsetX = { -it })
    },
    popTransitionSpec = {
        slideInHorizontally(initialOffsetX = { -it }) togetherWith
            slideOutHorizontally(targetOffsetX = { it })
    },
    predictivePopTransitionSpec = {
        slideInHorizontally(initialOffsetX = { -it }) togetherWith
            slideOutHorizontally(targetOffsetX = { it })
    }
)
```

For full player dialog custom animation, use metadata:
```kotlin
entry<FullPlayerKey>(
    metadata = metadata {
        put(NavDisplay.DialogKey, true)
        put(NavDisplay.TransitionKey) {
            fadeIn(tween(300)) + scaleIn(initialScale = 0.92f, tween(300)) togetherWith
                fadeOut(tween(200)) + scaleOut(targetScale = 1.08f, tween(200))
        }
    }
) { /* ... */ }
```

---

## Phase 8: Remove Old Code

### Delete files:
- `app/src/main/java/pw/kmr/sonnet/core/ui/AppDestination.kt`

### Remove from `SonnetApp.kt`:
- `PlayerOverlayRequest` data class
- `PlayerSurface` sealed interface
- `playerOverlay` mutable state
- `showPlayerOverlay`, `showMiniPlayer` computed values
- `playerSurface` computed value
- `AnimatedContent` player transition block (lines 145-185)
- `BackHandler` for player overlay (lines 118-120)
- `navController`, `rememberNavController()`, `currentBackStackEntryAsState()`
- `NavHost` block (lines 123-143)
- All Nav2 imports (`NavHost`, `composable`, `currentBackStackEntryAsState`, `rememberNavController`)

### Keep unchanged:
- `MiniPlayer` composable (move to separate file or keep and make non-private)
- `MiniPlayerCover` composable
- `LoadingApp` composable
- `AppContainer`, `MainActivity`, `SonnetApplication`
- `LoginRoute`, `LibraryRoute`, `PlayerRoute` (composable functions unchanged)
- `PlaybackController`, `AppViewModel`

---

## Files Summary

| File | Action |
|------|--------|
| `gradle/libs.versions.toml` | Add Nav3 versions + libraries |
| `app/build.gradle.kts` | Swap Nav2 → Nav3 deps, apply serialization plugin |
| `core/ui/NavKeys.kt` | **New** — `LoginKey`, `LibraryKey`, `FullPlayerKey` |
| `core/ui/AppDestination.kt` | **Delete** |
| `core/ui/SonnetApp.kt` | Major rewrite — Nav3 back stack, entryProvider, NavDisplay, remove overlay logic |
| `core/ui/MiniPlayerDecoratorStrategy.kt` | **New** — scene decorator for mini player |

---

## Fallback Plan

If `DialogSceneStrategy` doesn't work well for the full player (e.g., dialog margins, not truly full-screen):

1. **Quick fallback**: Make `FullPlayerKey` a regular NavEntry (no dialog metadata). The mini player decorator hides itself when `FullPlayerKey` is present. Standard slide transition. Predictive back still works.

2. **Keep overlay**: Revert to `AnimatedContent` overlay for the player outside NavDisplay, use Nav3 only for Login/Library. Manual `BackHandler` for player predictive back.

---

## Open Questions / Risks

1. **DialogSceneStrategy full-screen**: Default dialog may have padding. May need to override dialog container styling. Test early.
2. **Mini player decorator key**: Must derive key from inner scene (`inner::class to inner.key`) to maintain proper Nav3 animations.
3. **Auth redirect from background**: If tokens expire while on Library, need to clear back stack and go to Login. Test the `LaunchedEffect(isAuthenticated)` pattern with Nav3.
4. **`isDownloaded` in FullPlayerKey**: Currently `onOpenPlayer` passes `book.isDownloaded` but mini player tap uses `true` as default. May need to track this in `PlayerUiState`.
5. **MiniPlayer composable access**: Currently `private` in SonnetApp.kt. Need to make accessible for the decorator strategy.
