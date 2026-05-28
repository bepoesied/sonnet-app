# 08: Add Shared Secure Session Boundary

## Goal

Prepare auth/session logic to move into shared code without weakening token storage.

## Context

The current `SessionRepository` uses Android keystore and `SharedPreferences`. That implementation should not move to common code as-is because iOS should use Keychain and Android should keep platform-backed encryption.

The reusable behavior is token/session orchestration: current session flow, bootstrap, refresh, auth retry, requires-login state, and clearing session.

## Scope

- Add shared `SessionStore` interface.
- Move `AuthSessionManager` to shared.
- Make `AuthSessionManager` depend on `SessionStore` and an `AuthRemoteDataSource` interface.
- Keep Android secure token storage implementation in `:app` behind `SessionStore`.
- Keep Android keystore encryption unchanged.

## Out Of Scope

- Moving AppAuth browser login.
- Moving Android `Intent` handling.
- Replacing Android secure storage.
- Implementing iOS Keychain storage now.

## Expected Result

- Token refresh, auth retry, and bootstrap behavior become shared.
- Token persistence remains platform-secure.
- Android auth behavior remains unchanged.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Test cold-start session bootstrap on Android.
- Test logout and login-required behavior.