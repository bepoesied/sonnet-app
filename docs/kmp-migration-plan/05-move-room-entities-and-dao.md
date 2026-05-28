# 05: Move Room Entities And DAO

## Goal

Move database schema declarations to common code while preserving Android behavior.

## Context

The current Room entities and `LibraryDao` are already close to KMP-compatible. DAO reads that return reactive data already use `Flow`, and non-reactive database calls are already `suspend`.

This slice should make shared own the schema types but can leave database construction in Android if that keeps the change smaller.

## Scope

- Move `LibraryItemEntity` to `shared/commonMain`.
- Move `DownloadEntity` to `shared/commonMain`.
- Move `DownloadedBookEntity` to `shared/commonMain`.
- Move `DownloadedChapterEntity` to `shared/commonMain`.
- Move `PlaybackProgressEntity` to `shared/commonMain`.
- Move `LibraryDao` to `shared/commonMain`.
- Keep DAO signatures coroutine/Flow-based.
- Update Android imports.
- Keep `SonnetDatabase` construction in Android for this slice if that makes the move smaller.

## Out Of Scope

- Moving `SonnetDatabase`.
- Moving migrations.
- Changing database version.
- Changing table or column names.
- Changing download or playback behavior.

## Expected Result

- The same Android database schema is described by shared code.
- Android still uses the existing database file and data.
- No schema migration should be required because schema shape is unchanged.

## Verification

- Run Room/KSP shared build tasks.
- Run `./gradlew :app:assembleDebug`.
- Launch Android and verify the library still reads from local storage.
- Confirm no generated schema diff is introduced unless intentionally caused by export settings.