# 07: Move DataStore Settings

## Goal

Make non-secret settings shared through DataStore Preferences KMP.

## Context

DataStore Preferences supports KMP from `1.1.0+`, and the project uses DataStore `1.2.1`. App settings like server URL and playback sync cadence are not platform-specific and should be shared.

Auth tokens are different. Raw tokens should not move into a general settings DataStore unless a platform encryption design is added first.

## Scope

- Add DataStore KMP dependencies to `:shared`.
- Move `AppSettingsRepository` to `shared/commonMain`.
- Change `AppSettingsRepository` to accept `DataStore<Preferences>` instead of Android `Context`.
- Add a shared DataStore factory wrapper.
- Add Android DataStore creation using `FileStorage` and `context.filesDir`.
- Add iOS DataStore creation using `OkioStorage` and `NSDocumentDirectory`.
- Update Android `AppContainer` to provide the shared settings repository.

## Out Of Scope

- Moving secure session token storage.
- Changing logout behavior except where it interacts with saved server URL.
- Adding new settings.

## Expected Result

- App settings behavior is shared.
- Android settings remain functionally equivalent.
- Saved server URL still survives logout.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Verify saved server URL still appears on the login screen.
- Verify logout still retains the chosen server URL.