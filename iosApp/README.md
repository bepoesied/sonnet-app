# Sonnet for iOS

Native SwiftUI iOS client for Sonnet backed by the shared Kotlin Multiplatform module (`:shared`, exported as `sharedKit`).

## Requirements

- macOS with Xcode 16+
- JDK 21 (for Gradle / Kotlin Multiplatform framework builds)
- iOS 17.0+ deployment target (iPhone & iPad)

## Building & Running

1. Open `iosApp/Sonnet.xcodeproj` in Xcode.
2. Select a development team in **Signing & Capabilities** if running on a physical iOS device (not required for Simulator).
3. Select the **Sonnet** scheme and an iOS Simulator (e.g. iPhone 16) and press **Run (`Cmd + R`)**.

Xcode automatically invokes `./gradlew :shared:embedAndSignAppleFrameworkForXcode` during the **Compile Kotlin Framework** build phase before compiling Swift sources.

## Architecture

- **Shared KMP (`:shared`)**: Room KMP database, DataStore preferences, Ktor (`Darwin` engine) API clients, OIDC PKCE S256 protocol & token exchange, offline-first library/download repository, playback timeline math & orchestrator, progress reconciliation & sync coordinator, and shared ViewModels (`AppViewModel`, `LoginViewModel`, `LibraryViewModel`, `PlayerViewModel`).
- **Native iOS Platform Services (`iosApp/Sonnet/Platform/`)**:
  - `KeychainSecureStore.swift`: Keychain token storage (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) + `UserDefaults` session metadata.
  - `CryptoKitOidcCrypto.swift`: `SecRandomCopyBytes` + `CryptoKit.SHA256` for OIDC PKCE.
  - `WebAuthSessionPresenter.swift`: `ASWebAuthenticationSession` with custom `sonnet://auth/callback` scheme.
  - `AVFoundationPlaybackDriver.swift`: `AVAudioSession` (`.playback` / `.spokenAudio`), `AVPlayer` chapter playback, `MPNowPlayingInfoCenter`, and `MPRemoteCommandCenter` lock screen / Control Center controls.
  - `NWPathNetworkMonitor.swift`: `NWPathMonitor` reachability bridge.
  - `IosBackgroundTasks.swift`: `UIApplication.beginBackgroundTask` download protection and `BGTaskScheduler` (`pw.kmr.sonnet.progress-sync`, `pw.kmr.sonnet.download`).
  - `CoverImageLoader.swift`: Local-first book cover loader with stable `bookId`-keyed memory/disk cache.
- **Native SwiftUI (`iosApp/Sonnet/UI/`)**: SwiftUI screens (`LoginView`, `LibraryView`, `MiniPlayerBar`, `PlayerView` with chapters sheet, sleep timer sheet, and server resume prompt) driven by `@Observable` stores in `State/AppStores.swift`.
