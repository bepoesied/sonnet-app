import AuthenticationServices
import Foundation
import UIKit
import sharedKit

/// Presents the native iOS `ASWebAuthenticationSession` browser sheet for OIDC Authorization Code + PKCE.
@MainActor
final class WebAuthSessionPresenter: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var activeSession: ASWebAuthenticationSession?

    /// Opens the OIDC authorization URL in the native system browser auth session.
    ///
    /// Returns an `OidcAuthorizationResult` on callback, or `nil` if the user cancels.
    func authenticate(request: OidcAuthorizationRequest) async -> OidcAuthorizationResult? {
        guard let url = URL(string: request.authorizationUrl) else {
            return nil
        }

        return await withCheckedContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: request.callbackScheme
            ) { [weak self] callbackURL, error in
                Task { @MainActor [weak self] in
                    self?.activeSession = nil
                    if let callbackURL {
                        continuation.resume(
                            returning: OidcAuthorizationResult(
                                callbackUrl: callbackURL.absoluteString,
                                codeVerifier: request.codeVerifier,
                                expectedState: request.state
                            )
                        )
                    } else if let authError = error as? ASWebAuthenticationSessionError,
                              authError.code == .canceledLogin {
                        continuation.resume(returning: nil)
                    } else if let error {
                        // Surface non-cancellation errors through callback URL error params
                        // so LoginViewModel maps them cleanly into user-facing state.
                        var components = URLComponents()
                        components.scheme = request.callbackScheme
                        components.host = "auth"
                        components.path = "/callback"
                        components.queryItems = [
                            URLQueryItem(name: "error", value: "session_failed"),
                            URLQueryItem(name: "error_description", value: error.localizedDescription),
                            URLQueryItem(name: "state", value: request.state)
                        ]
                        continuation.resume(
                            returning: OidcAuthorizationResult(
                                callbackUrl: components.url?.absoluteString ?? "",
                                codeVerifier: request.codeVerifier,
                                expectedState: request.state
                            )
                        )
                    } else {
                        continuation.resume(returning: nil)
                    }
                }
            }

            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.activeSession = session

            if !session.start() {
                self.activeSession = nil
                continuation.resume(returning: nil)
            }
        }
    }

    func cancel() {
        activeSession?.cancel()
        activeSession = nil
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            if let keyWindow = scenes
                .flatMap(\.windows)
                .first(where: { $0.isKeyWindow }) {
                return keyWindow
            }
            if let firstWindow = scenes.first?.windows.first {
                return firstWindow
            }
            if let firstScene = scenes.first {
                return UIWindow(windowScene: firstScene)
            }
            return ASPresentationAnchor()
        }
    }
}
