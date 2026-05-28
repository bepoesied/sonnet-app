# 02: Move Pure Core Models

## Goal

Move platform-neutral data models to `:shared` so later slices have stable common types.

## Context

Several models are already pure Kotlin and do not need Android APIs. Moving them early lowers the complexity of later Room, API, auth, library, and playback slices.

This slice should only change package ownership and imports. It should not change behavior.

## Scope

- Move `AuthSession`, `SonnetUser`, and `AppSettings` to `shared/src/commonMain`.
- Move `LibraryBook`, `DownloadedBook`, `DownloadedChapter`, and `DownloadStatus` to `shared/src/commonMain`.
- Move API/domain models to `shared/src/commonMain`, including `BookSummary`, `BookDetail`, `BookChapter`, `RemoteProgress`, `MobileConfig`, `LoginResponse`, and `TokenRefreshResponse`.
- Update Android imports in `:app`.

## Out Of Scope

- Moving repositories.
- Moving API clients.
- Adding serialization annotations unless required by moved code.
- Changing database schema.
- Changing UI state models unless they are pure and needed by the moved code.

## Expected Result

- Android behavior remains unchanged.
- Shared models become the canonical types used by the Android app.
- Later slices can depend on shared models without duplicating types.

## Verification

- Run shared common tests if present.
- Run `./gradlew :app:assembleDebug`.
- Confirm no model copy or adapter layer was added unnecessarily.