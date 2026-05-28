# 06: Move Room Database And Migrations

## Goal

Make shared fully own the Room database definition and migration logic.

## Context

After entities and DAO are common, the next step is moving `SonnetDatabase` and migrations into `shared/commonMain`. Only database builder path creation should remain platform-specific because Android and iOS use different filesystem APIs.

Android must keep using the same `sonnet.db` location to preserve installed user data.

## Scope

- Move `SonnetDatabase` to `shared/commonMain`.
- Add `@ConstructedBy` and `RoomDatabaseConstructor` expect object.
- Convert migrations to Room KMP `SQLiteConnection` APIs if they currently use Android-only migration APIs.
- Add common `buildSonnetDatabase(builder)` using `BundledSQLiteDriver` and `Dispatchers.IO`.
- Add `shared/androidMain` database builder using `Context.getDatabasePath("sonnet.db")`.
- Add `shared/iosMain` database builder using `NSDocumentDirectory`.
- Update Android `AppContainer` to construct the database through shared APIs.

## Out Of Scope

- Changing schema version except as required by an intentional migration.
- Changing database filename.
- Moving repositories.
- Moving sync logic.

## Expected Result

- Room database definition and migration behavior are shared.
- Android keeps the same `sonnet.db` location and should preserve existing installed data.
- iOS has a database builder ready for future app wiring.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Test Android install over an existing app database if possible.
- Verify library, downloads metadata, and playback progress still load.