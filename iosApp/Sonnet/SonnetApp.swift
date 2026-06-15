import SwiftUI
import sharedKit

@main
struct SonnetApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
                .onOpenURL { url in
                    appState.handleCallback(url: url)
                }
                .onAppear {
                    appState.bootstrap()
                }
        }
    }
}

enum AppRoute: Hashable {
    case library
    case player(bookId: String, isDownloaded: Bool)
}

@MainActor
class AppState: ObservableObject {
    @Published var isLoading = true
    @Published var isAuthenticated = false
    @Published var navigationPath = NavigationPath()

    let container: AppContainer

    init() {
        self.container = AppContainer()
    }

    func bootstrap() {
        container.appHelper.bootstrapSession {
            self.isLoading = false
            self.isAuthenticated = self.container.sessionRepository.currentSessionValue != nil
        }
    }

    func logout() {
        container.playbackHelper.shutdown()
        container.appHelper.logout {
            self.isAuthenticated = false
            self.navigationPath = NavigationPath()
        }
    }

    func handleCallback(url: URL) {
        let urlString = url.absoluteString
        guard urlString.hasPrefix("sonnet://auth/callback") else { return }
        guard let authData = IosPlatformAuthProvider.companion.currentAuthData else { return }

        let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        let code = queryItems?.first(where: { $0.name == "code" })?.value
        let state = queryItems?.first(where: { $0.name == "state" })?.value

        guard let code = code else { return }

        let result = AuthCallbackResult(code: code, state: state, authData: authData)

        container.loginHelper.completeLogin(authResult: result, onSuccess: {
            self.isAuthenticated = true
            self.navigationPath = NavigationPath()
        }, onError: { _ in })
    }

    func openPlayer(bookId: String, isDownloaded: Bool) {
        navigationPath.append(AppRoute.player(bookId: bookId, isDownloaded: isDownloaded))
    }
}

struct ContentView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        if appState.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            AppNavigationView()
                .environmentObject(appState)
        }
    }
}

struct AppNavigationView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        NavigationStack(path: $appState.navigationPath) {
            Group {
                if appState.isAuthenticated {
                    LibraryView()
                        .navigationDestination(for: AppRoute.self) { route in
                            switch route {
                            case .library:
                                LibraryView()
                            case .player(let bookId, let isDownloaded):
                                PlayerView(bookId: bookId, isDownloaded: isDownloaded)
                            }
                        }
                } else {
                    LoginView()
                }
            }
            .overlay(alignment: .bottom) {
                if appState.isAuthenticated {
                    MiniPlayerOverlay()
                }
            }
        }
    }
}
