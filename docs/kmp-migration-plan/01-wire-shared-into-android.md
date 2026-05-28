# 01: Wire Shared Into Android App

## Goal

Make `:shared` a real dependency of `:app` without moving behavior.

This is intentionally small. It proves the Gradle project shape is sound before any code migration starts.

## Context

The shared module has already been initialized by Android Studio and is included from `settings.gradle.kts` as `include(":shared")`. The Android app currently does not need shared functionality yet, but every later migration slice depends on `:app` being able to compile against `:shared`.

Target platforms are Android and iOS only.

## Scope

- Add `implementation(project(":shared"))` to `:app`.
- Verify `:shared` is configured for Android and iOS only.
- Keep existing Android app behavior unchanged.
- Import and call one harmless shared symbol only if needed to prove wiring, then remove it if it has no real purpose.

## Out Of Scope

- Moving models.
- Moving Room.
- Moving DataStore.
- Moving APIs.
- Changing Android UI, playback, auth, or sync behavior.

## Expected Result

- `:app` can compile against `:shared`.
- No runtime behavior changes.
- This slice is safe to revert independently if Gradle wiring is wrong.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Optionally launch Android app and confirm startup behavior is unchanged.