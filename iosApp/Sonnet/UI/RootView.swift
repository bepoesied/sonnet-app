import SwiftUI
import sharedKit

struct PlayerDestination: Identifiable, Equatable {
    let bookId: String
    let isDownloaded: Bool

    var id: String { bookId }
}

struct RootView: View {
    let appStore: AppStore

    @State private var playerDestination: PlayerDestination?
    @State private var showReauthSheet: Bool = false

    var body: some View {
        Group {
            if appStore.isLoading {
                Color(uiColor: .systemBackground)
                    .ignoresSafeArea()
            } else if appStore.isAuthenticated {
                LibraryView(
                    container: appStore.container,
                    onOpenPlayer: { book in
                        playerDestination = PlayerDestination(
                            bookId: book.id,
                            isDownloaded: book.isDownloaded
                        )
                    },
                    onLogout: {
                        playerDestination = nil
                        showReauthSheet = false
                        appStore.logout()
                    },
                    onReauthenticate: {
                        showReauthSheet = true
                    }
                )
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if appStore.hasActiveBook, let activeBookId = appStore.activePlayerState.bookId {
                        MiniPlayerBar(
                            state: appStore.activePlayerState,
                            onOpenPlayer: {
                                playerDestination = PlayerDestination(
                                    bookId: activeBookId,
                                    isDownloaded: true
                                )
                            },
                            onPlayPause: {
                                appStore.toggleActivePlayback()
                            }
                        )
                    }
                }
                .sheet(item: $playerDestination) { destination in
                    PlayerView(
                        bookId: destination.bookId,
                        isDownloaded: destination.isDownloaded,
                        container: appStore.container,
                        onDismiss: {
                            playerDestination = nil
                        }
                    )
                    .id(destination.bookId)
                    .presentationDragIndicator(.visible)
                }
                .sheet(isPresented: $showReauthSheet) {
                    LoginView(
                        container: appStore.container,
                        authPresenter: appStore.authPresenter,
                        showCancelButton: true,
                        onCancel: {
                            showReauthSheet = false
                        },
                        onLoginCompleted: {
                            showReauthSheet = false
                        }
                    )
                }
                .onChange(of: appStore.pendingPlayerRequest) { _, newValue in
                    guard newValue != nil, let bookId = appStore.consumePendingPlayerRequest() else {
                        return
                    }
                    playerDestination = PlayerDestination(bookId: bookId, isDownloaded: true)
                }
                .onAppear {
                    if let bookId = appStore.consumePendingPlayerRequest() {
                        playerDestination = PlayerDestination(bookId: bookId, isDownloaded: true)
                    }
                }
            } else {
                LoginView(
                    container: appStore.container,
                    authPresenter: appStore.authPresenter
                )
            }
        }
        .tint(SonnetColors.accent)
        .onChange(of: appStore.isAuthenticated) { _, authenticated in
            if !authenticated {
                playerDestination = nil
                showReauthSheet = false
            }
        }
        .onChange(of: appStore.requiresReauth) { _, needsReauth in
            if !needsReauth {
                showReauthSheet = false
            }
        }
    }
}
