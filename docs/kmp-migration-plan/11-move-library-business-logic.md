# 11: Move Library Business Logic

## Goal

Share library projection, refresh, completion, and local metadata behavior.

## Context

`LibraryRepository` currently combines reusable business rules with Android file paths and file deletion. Most library behavior should be shared, but raw file I/O can remain platform-adapted.

The shared database may continue storing `audioFilePath` and `coverFilePath` as opaque strings. Android can store absolute paths today; iOS can later store app-container paths or file URLs.

## Scope

- Move library projection logic from `LibraryRepository` to shared.
- Move refresh metadata logic to shared.
- Move completion/incompletion logic to shared.
- Move downloaded metadata reads to shared.
- Keep file download execution and file deletion platform-specific behind small interfaces.
- Keep Android download storage paths unchanged.

## Out Of Scope

- Moving full file download orchestration unless it naturally falls out behind small adapters.
- Changing download table schema.
- Changing offline playback requirement that only downloaded books are playable.
- Changing UI gestures.

## Expected Result

- Most library behavior is shared.
- Android-specific file I/O remains isolated.
- Android library screen behavior remains unchanged.

## Verification

- Add common tests for library projection: remote-only, downloaded, local-only, failed/downloading states, progress percentage, completion precedence.
- Run shared tests.
- Run `./gradlew :app:assembleDebug`.
- Test refresh, swipe complete/incomplete, download/delete, and local-only downloaded books on Android.