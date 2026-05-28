# Kotlin Multiplatform Migration Plan

## Goal

Reuse as much app behavior as practical across Android and iOS while keeping UI and platform integrations native.

Shared code should own durable app behavior: persistence, preferences, auth/session logic, library state, progress sync rules, playback timeline math, API DTOs, and platform-neutral ViewModels where appropriate.

Platform code should own UI, audio playback, browser authentication handoff, secure token storage, filesystem roots, notifications, foreground services, and background scheduling.

Prefer sharing behavior by default. Keep code platform-specific only when it directly depends on platform APIs, user-interface frameworks, audio playback engines, secure storage primitives, or OS scheduling/lifecycle behavior.

## Target Platforms

- Android
- iOS

Desktop/JVM is not part of this plan.

## Current Codebase Shape

The app is currently an Android app with a newly initialized `:shared` module. Most business logic still lives in `:app` and often depends directly on Android APIs.

Business logic currently mixed into Android code:

- `library/LibraryRepository.kt`: library projection, refresh, download lifecycle, completion state, local/remote merge logic.
- `sync/ProgressSyncer.kt`: pending playback progress sync behavior.
- `player/PlaybackController.kt`: Media3 control mixed with reusable audiobook position math and progress reconciliation.
- `auth/AuthSessionManager.kt`: token refresh, auth retry, session bootstrap.
- `auth/AuthRepository.kt`: server URL normalization, OIDC validation, login/logout flow mixed with AppAuth.
- `data/remote/*.kt`: API contract, JSON parsing, auth API, books API.
- `data/preferences/*.kt`: settings and session storage, currently Android-specific.
- `data/local/*.kt`: Room entities, DAO, database, migrations.
- `library/LibraryViewModel.kt`, `LoginViewModel.kt`, `AppViewModel.kt`: presentation state coupled to Android lifecycle APIs.

Android-specific code that should remain platform code:

- Jetpack Compose routes, components, theme, navigation, and Android activity setup.
- Media3/ExoPlayer playback implementation and `SonnetMediaSessionService`.
- AppAuth `AuthorizationService`, `Intent`, and Android activity result launcher glue.
- Android secure token storage using keystore.
- Android file storage roots and file deletion.
- Connectivity callbacks, WorkManager, foreground services, notifications, and Android Auto/media session integrations.

## Target Module Split

```text
:shared
  commonMain
    domain models
    Room database, entities, DAOs, migrations
    DataStore settings repository
    API DTOs and remote client abstractions/implementations
    auth/session manager
    library use cases
    progress sync rules
    playback timeline/progress math
    selected AndroidX ViewModel KMP ViewModels
  androidMain
    Room database builder path
    DataStore file storage creation
    Android platform adapters when useful
  iosMain
    Room database builder path
    DataStore file storage creation
    SwiftUI/ViewModel interop helpers later

:app
  Android Compose UI
  Android navigation
  Media3/ExoPlayer playback
  AppAuth implementation
  Android secure session store
  Android file download/storage implementation
  Android dependency wiring
```

Dependency direction:

```text
Compose UI -> shared ViewModel/use case -> shared repository/domain service -> platform adapter
```

## Shared Technology Choices

- Room KMP for local database. Room supports KMP from `2.7.0+`; this project uses `2.8.4`.
- DataStore Preferences KMP for non-secret settings. DataStore supports KMP from `1.1.0+`; this project uses `1.2.1`.
- AndroidX ViewModel KMP for selected shared presentation state. ViewModel supports KMP from `2.8.0+`; this project uses lifecycle `2.10.0`.
- Ktor plus kotlinx.serialization for shared API clients.
- Android keystore remains Android-specific for token encryption.
- iOS Keychain should back token storage later.

## Slice Index

Execute these one at a time. Each slice should build cleanly before moving to the next.

1. `01-wire-shared-into-android.md`
2. `02-move-pure-core-models.md`
3. `03-move-playback-timeline-math.md`
4. `04-configure-room-kmp.md`
5. `05-move-room-entities-and-dao.md`
6. `06-move-room-database-and-migrations.md`
7. `07-move-datastore-settings.md`
8. `08-add-shared-secure-session-boundary.md`
9. `09-move-remote-api-to-shared-ktor.md`
10. `10-move-progress-sync-logic.md`
11. `11-move-library-business-logic.md`
12. `12-move-shared-viewmodels-for-app-and-library.md`
13. `13-split-and-move-login-state-logic.md`
14. `14-move-playback-reconciliation-logic.md`
15. `15-add-ios-consumption-scaffolding.md`

## Dependency Versions To Plan For

- Kotlin: current project uses `2.2.10`; avoid jumping to release candidates without a blocker.
- Room: `2.8.4`.
- SQLite bundled: `2.6.2`.
- DataStore: `1.2.1`.
- Lifecycle/ViewModel: `2.10.0`.
- Ktor: latest checked `3.5.0`.
- kotlinx.serialization JSON: latest checked `1.11.0`.
- kotlinx.coroutines: latest checked `1.11.0`.

KSP must be configured for every active KMP target. For this project that means Android and iOS only, for example `kspAndroid`, `kspIosSimulatorArm64`, `kspIosX64`, and `kspIosArm64` if all three iOS targets are enabled.

## Open Decisions

- Decide whether Android secure session storage remains `SharedPreferences` plus keystore or moves to encrypted payloads in DataStore.
- Choose SKIE or KMP-NativeCoroutines before SwiftUI starts observing shared `Flow` or `StateFlow`.
- Decide when full download orchestration should move shared. The preferred direction is to share as much of it as possible, leaving only filesystem path creation and byte writing as platform adapters.