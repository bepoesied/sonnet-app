# 13: Split And Move Login State Logic

## Goal

Share login state and validation while leaving browser/OIDC launch platform-native.

## Context

Current `LoginViewModel` exposes Android `Intent` through `LoginEffect`, so it cannot move directly to common code. The shared part is server URL input state, loading/error state, mobile config validation, and pending login metadata. Platform code should launch the browser auth flow.

Android should continue using AppAuth. iOS can later map the same platform-neutral auth request to `ASWebAuthenticationSession` or an equivalent native browser auth flow.

## Scope

- Move server URL input state to shared.
- Move loading/error state to shared.
- Move mobile config validation to shared.
- Move pending login metadata to shared.
- Replace Android `Intent` effects with platform-neutral auth effects.
- Keep Android AppAuth request creation, browser launch, activity result, and token exchange in `:app` or an Android adapter.
- Add an iOS-facing auth adapter shape for future `ASWebAuthenticationSession` implementation.

## Out Of Scope

- Removing AppAuth.
- Implementing iOS auth UI now.
- Changing server OIDC contract.
- Changing redirect scheme behavior.

## Expected Result

- Login business state is shared.
- Android login flow still uses AppAuth and behaves the same.
- iOS has a clear adapter seam for future browser auth.

## Verification

- Run `./gradlew :shared:build`.
- Run `./gradlew :app:assembleDebug`.
- Test valid login, invalid server URL, cancelled login, and login result handling on Android.