# 12: Move Shared ViewModels For App And Library

## Goal

Share presentation state that does not require platform UI or audio APIs.

## Context

AndroidX ViewModel supports KMP from `2.8.0+`, and this project uses lifecycle `2.10.0`. Shared ViewModels can be consumed by Android now and by SwiftUI later with a small iOS ViewModelStore owner and resolver.

Do not move audio playback control yet. Media3 and service-bound playback remain Android-specific.

## Scope

- Add AndroidX ViewModel KMP dependency as `api` in `:shared`.
- Move `AppViewModel` to shared after auth/session dependencies are shared.
- Move `LibraryViewModel` to shared after library use cases are shared.
- Keep Android Compose screens in `:app` and continue collecting state with lifecycle-aware Compose APIs.
- Configure iOS framework export for ViewModel.

## Out Of Scope

- Moving `PlayerViewModel`.
- Moving `PlaybackController`.
- Building SwiftUI screens.
- Choosing Flow observation tooling unless needed for framework export validation.

## Expected Result

- Android UI uses shared ViewModels.
- iOS can later consume the same ViewModels from SwiftUI.
- App and library presentation behavior remains unchanged on Android.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Test app startup, session state routing, library refresh, error display, completion swipe, and download swipe on Android.