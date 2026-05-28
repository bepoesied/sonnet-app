# 04: Configure Room KMP In Shared

## Goal

Make `:shared` ready to own the Room database before moving the schema.

## Context

Room supports KMP from `2.7.0+`, and the project already uses Room `2.8.4`. Local persistence should be shared instead of hidden behind Android-only Room adapters.

This slice only configures dependencies and build tooling. The Android app should still use its existing database code at the end of this slice.

## Scope

- Add Room KMP runtime to `:shared`.
- Add bundled SQLite to `:shared`.
- Add the Room Gradle plugin to `:shared`.
- Add KSP configuration for Android and iOS targets only.
- Configure the Room schema directory.
- Do not move Android database code yet.

## Out Of Scope

- Moving entities.
- Moving DAOs.
- Moving migrations.
- Changing Android `AppContainer` database wiring.
- Changing database file location.

## Expected Result

- Shared module can run Room KSP for Android and iOS targets.
- Android app remains on its existing Room database for this slice.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Confirm no app runtime behavior changed.