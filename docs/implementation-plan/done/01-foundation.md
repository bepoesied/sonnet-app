# 01 Foundation

## Goal

Turn the starter Android app into a usable application shell with the base architecture needed for auth, downloads, and playback.

## Scope

- Replace the demo `MainActivity` UI with the real app root.
- Establish app architecture and state ownership.
- Add navigation, dependency injection or a lightweight service container, persistence, networking, and image loading foundations.
- Prepare release/debug network policy differences.

## Deliverables

- App root with navigation destinations for:
  - login
  - library
  - player
- Shared app theme using Compose Material 3.
- A package layout that keeps auth, library, downloads, playback, and sync separate.
- A stable place for app-wide state such as current session and configured server URL.
- Build configuration that distinguishes debug and release network behavior.

## Recommended Technical Decisions

- Use Compose Navigation for app flow.
- Use a single-activity architecture.
- Use ViewModels plus Kotlin coroutines and Flow.
- Use Room for durable local metadata and progress state.
- Use DataStore for lightweight app preferences such as server URL and playback sync cadence.
- Use OkHttp + Retrofit or a small direct OkHttp client for the JSON API.
- Use Coil for image loading and caching.
- Use Media3 for playback, media session, and notification integration.

## Open Design Decisions To Lock Early

- Whether to use Hilt or keep dependency injection manual.
- Whether to model downloads entirely in Room first or allow a simpler file-system-first metadata layer for v1.
- Whether library state should come from a single offline-first repository or separate online/local sources merged in ViewModel.

## Suggested Tasks

1. Add core dependencies.
2. Lower `minSdk` to a realistic supported value.
3. Replace the sample screen with an app root that selects login or library based on persisted auth state.
4. Create initial packages:
   - `auth`
   - `library`
   - `downloads`
   - `player`
   - `data/local`
   - `data/remote`
   - `core/model`
   - `core/ui`
5. Add DataStore for server URL and app settings.
6. Add Room database shell and entities placeholders.
7. Add debug-only cleartext network allowance and release-only HTTPS enforcement hooks.

## Definition Of Done

- App launches into a real navigation shell.
- No sample/demo UI remains.
- App has enough structure that auth can be built without reworking the root.
- Dependency and package choices are settled for the rest of the milestone.