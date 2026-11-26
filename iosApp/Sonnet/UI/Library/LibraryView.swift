import SwiftUI
import sharedKit

struct LibraryView: View {
    private let container: IosAppContainer
    private let onOpenPlayer: (LibraryBook) -> Void
    private let onLogout: () -> Void
    private let onReauthenticate: () -> Void

    @State private var store: LibraryStore?

    init(
        container: IosAppContainer,
        onOpenPlayer: @escaping (LibraryBook) -> Void,
        onLogout: @escaping () -> Void,
        onReauthenticate: @escaping () -> Void
    ) {
        self.container = container
        self.onOpenPlayer = onOpenPlayer
        self.onLogout = onLogout
        self.onReauthenticate = onReauthenticate
    }

    var body: some View {
        Group {
            if let store {
                LibraryContentScreen(
                    store: store,
                    onOpenPlayer: onOpenPlayer,
                    onLogout: onLogout,
                    onReauthenticate: onReauthenticate
                )
            } else {
                NavigationStack {
                    List {
                        ForEach(0..<7, id: \.self) { _ in
                            LibrarySkeletonRow()
                                .listRowSeparator(.visible)
                        }
                    }
                    .listStyle(.plain)
                    .disabled(true)
                    .navigationTitle("Library")
                }
            }
        }
        .onAppear {
            if store == nil {
                store = LibraryStore(container: container)
            }
        }
    }
}

private struct LibraryContentScreen: View {
    let store: LibraryStore
    let onOpenPlayer: (LibraryBook) -> Void
    let onLogout: () -> Void
    let onReauthenticate: () -> Void

    @State private var showLogoutConfirmation = false
    @State private var bookPendingDelete: LibraryBook?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                contentBody
                    .navigationTitle("Library")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Log out") {
                                showLogoutConfirmation = true
                            }
                        }
                    }
                    .searchable(
                        text: Binding(
                            get: { store.uiState.searchQuery },
                            set: { store.updateSearchQuery($0) }
                        ),
                        placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Search books..."
                    )
                    .refreshable {
                        await store.refreshAndWait()
                    }

                if let errorMessage = store.uiState.errorMessage, !errorMessage.isEmpty {
                    ErrorToastBanner(message: errorMessage) {
                        store.clearError()
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: errorMessage) {
                        try? await Task.sleep(for: .seconds(4))
                        if store.uiState.errorMessage == errorMessage {
                            store.clearError()
                        }
                    }
                }
            }
            .animation(.easeInOut(duration: 0.2), value: store.uiState.errorMessage)
            .alert("Log out?", isPresented: $showLogoutConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Log out", role: .destructive) {
                    onLogout()
                }
            } message: {
                Text("Logging out will remove all downloaded audiobooks from this device.")
            }
            .alert(
                "Delete download?",
                isPresented: Binding(
                    get: { bookPendingDelete != nil },
                    set: { if !$0 { bookPendingDelete = nil } }
                ),
                presenting: bookPendingDelete
            ) { book in
                Button("Cancel", role: .cancel) {
                    bookPendingDelete = nil
                }
                Button("Delete", role: .destructive) {
                    bookPendingDelete = nil
                    store.performDownloadAction(for: book)
                }
            } message: { book in
                Text(
                    "Remove downloaded audio files for \"\(book.title)\" from this device? Your listening progress will be kept."
                )
            }
        }
    }

    @ViewBuilder
    private var contentBody: some View {
        let uiState = store.uiState
        let queryBlank = uiState.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty

        if !uiState.initialLoadComplete || (uiState.books.isEmpty && uiState.isRefreshing && queryBlank) {
            List {
                statusBannersSection(uiState: uiState)
                ForEach(0..<7, id: \.self) { _ in
                    LibrarySkeletonRow()
                        .listRowSeparator(.visible)
                }
            }
            .listStyle(.plain)
            .disabled(true)
        } else if uiState.books.isEmpty && queryBlank {
            ScrollView {
                VStack(spacing: 12) {
                    statusBannersVStack(uiState: uiState)
                    EmptyLibraryView(
                        lastRefreshFailed: uiState.lastRefreshFailed,
                        isOnline: uiState.isOnline,
                        onRetry: { store.refresh(showProgress: true) }
                    )
                    .padding(.top, 80)
                }
                .frame(maxWidth: .infinity)
            }
        } else if uiState.books.isEmpty {
            VStack(spacing: 0) {
                statusBannersVStack(uiState: uiState)
                ContentUnavailableView(
                    "No matching books.",
                    systemImage: "magnifyingglass",
                    description: Text("Try a different title or author.")
                )
            }
        } else {
            List {
                statusBannersSection(uiState: uiState)

                ForEach(uiState.books, id: \.id) { book in
                    LibraryBookRow(
                        book: book,
                        onTap: {
                            if book.isDownloaded {
                                onOpenPlayer(book)
                            }
                        },
                        onDownloadAction: {
                            handleDownloadTap(book)
                        },
                        onCompletionAction: {
                            store.toggleCompletion(for: book)
                        }
                    )
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button {
                            store.toggleCompletion(for: book)
                        } label: {
                            Label(
                                book.isCompleted ? "Unplayed" : "Played",
                                systemImage: book.isCompleted ? "arrow.uturn.backward" : "checkmark.circle"
                            )
                        }
                        .tint(book.isCompleted ? .secondary : SonnetColors.accent)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        let isDestructive = book.downloadStatus == .downloaded
                            || book.downloadStatus == .queued
                            || book.downloadStatus == .downloading
                        Button(role: isDestructive ? .destructive : nil) {
                            handleDownloadTap(book)
                        } label: {
                            Label(
                                book.downloadStatus.actionLabel,
                                systemImage: downloadSwipeIcon(for: book.downloadStatus)
                            )
                        }
                        .tint(isDestructive ? .red : SonnetColors.accent)
                    }
                    .contextMenu {
                        if book.isDownloaded {
                            Button {
                                onOpenPlayer(book)
                            } label: {
                                Label("Play", systemImage: "play.fill")
                            }
                        }

                        Button {
                            store.toggleCompletion(for: book)
                        } label: {
                            Label(
                                book.isCompleted ? "Mark unplayed" : "Mark played",
                                systemImage: book.isCompleted ? "arrow.uturn.backward" : "checkmark.circle"
                            )
                        }

                        let isDestructive = book.downloadStatus == .downloaded
                            || book.downloadStatus == .queued
                            || book.downloadStatus == .downloading
                        Button(role: isDestructive ? .destructive : nil) {
                            handleDownloadTap(book)
                        } label: {
                            Label(
                                book.downloadStatus.actionLabel,
                                systemImage: downloadSwipeIcon(for: book.downloadStatus)
                            )
                        }
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    @ViewBuilder
    private func statusBannersSection(uiState: LibraryUiState) -> some View {
        if uiState.requiresReauth || !uiState.isOnline {
            Section {
                if uiState.requiresReauth {
                    ReauthBannerView(onSignInAgain: onReauthenticate)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowSeparator(.hidden)
                }
                if !uiState.isOnline {
                    OfflineBannerView()
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowSeparator(.hidden)
                }
            }
        }
    }

    @ViewBuilder
    private func statusBannersVStack(uiState: LibraryUiState) -> some View {
        if uiState.requiresReauth || !uiState.isOnline {
            VStack(spacing: 8) {
                if uiState.requiresReauth {
                    ReauthBannerView(onSignInAgain: onReauthenticate)
                }
                if !uiState.isOnline {
                    OfflineBannerView()
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
    }

    private func handleDownloadTap(_ book: LibraryBook) {
        if book.downloadStatus == .downloaded {
            bookPendingDelete = book
        } else {
            store.performDownloadAction(for: book)
        }
    }

    private func downloadSwipeIcon(for status: DownloadStatus) -> String {
        switch status {
        case .notDownloaded:
            return "arrow.down.circle"
        case .failed:
            return "arrow.clockwise"
        case .queued, .downloading:
            return "xmark.circle"
        case .downloaded:
            return "trash"
        default:
            return "arrow.down.circle"
        }
    }
}

private struct LibraryBookRow: View {
    let book: LibraryBook
    let onTap: () -> Void
    let onDownloadAction: () -> Void
    let onCompletionAction: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            BookCoverView(
                bookId: book.id,
                title: book.title,
                localCoverPath: book.localCoverUri,
                remoteCoverUrl: book.remoteCoverUrl,
                cornerRadius: 10
            )
            .frame(width: 68, height: 68)

            VStack(alignment: .leading, spacing: 5) {
                Text(book.title)
                    .font(.headline)
                    .lineLimit(1)

                if let author = book.author, !author.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(author)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                HStack(spacing: 10) {
                    Text(book.isCompleted ? "Completed" : "In progress")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(book.isCompleted ? SonnetColors.accent : .secondary)

                    Text(book.downloadStatus.statusLabel)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(book.downloadStatus.statusColor)
                }
                .padding(.top, 2)

                if book.downloadStatus == .downloading {
                    if let progress = book.downloadProgressValue {
                        ProgressView(value: progress)
                            .tint(SonnetColors.accent)
                            .padding(.top, 2)
                    } else {
                        HStack(spacing: 6) {
                            ProgressView()
                                .controlSize(.small)
                                .tint(SonnetColors.accent)
                            Text("Preparing download…")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.top, 2)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            trailingDownloadButton
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .accessibilityAction(named: book.downloadStatus.actionLabel) {
            onDownloadAction()
        }
        .accessibilityAction(named: book.isCompleted ? "Mark unplayed" : "Mark played") {
            onCompletionAction()
        }
    }

    @ViewBuilder
    private var trailingDownloadButton: some View {
        switch book.downloadStatus {
        case .notDownloaded:
            Button(action: onDownloadAction) {
                Image(systemName: "arrow.down.circle")
                    .font(.title3)
                    .foregroundStyle(SonnetColors.accent)
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Download \(book.title)")

        case .failed:
            Button(action: onDownloadAction) {
                Image(systemName: "arrow.clockwise.circle")
                    .font(.title3)
                    .foregroundStyle(.red)
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Retry download \(book.title)")

        case .queued, .downloading:
            Button(action: onDownloadAction) {
                Image(systemName: "xmark.circle")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Cancel download \(book.title)")

        case .downloaded:
            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .foregroundStyle(SonnetColors.accent)
                .frame(width: 40, height: 40)
                .accessibilityLabel("Downloaded")

        default:
            EmptyView()
        }
    }
}

private struct ReauthBannerView: View {
    let onSignInAgain: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(SonnetColors.onSecondaryContainer)

            Text("Session expired. Downloaded books stay playable.")
                .font(.footnote)
                .foregroundStyle(SonnetColors.onSecondaryContainer)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button("Sign in again", action: onSignInAgain)
                .font(.footnote.weight(.semibold))
                .buttonStyle(.borderless)
                .tint(SonnetColors.accent)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(SonnetColors.secondaryContainer)
        )
    }
}

private struct OfflineBannerView: View {
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "wifi.slash")
                .foregroundStyle(.secondary)

            Text("Offline — downloaded books remain playable.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(uiColor: .secondarySystemGroupedBackground))
        )
    }
}

private struct EmptyLibraryView: View {
    let lastRefreshFailed: Bool
    let isOnline: Bool
    let onRetry: () -> Void

    private var iconName: String {
        if !isOnline { return "wifi.slash" }
        if lastRefreshFailed { return "exclamationmark.circle" }
        return "books.vertical"
    }

    private var title: String {
        if !isOnline { return "You're offline" }
        if lastRefreshFailed { return "Library refresh failed" }
        return "No books yet"
    }

    private var subtitle: String {
        if !isOnline { return "Connect to the internet to load your library." }
        if lastRefreshFailed { return "Check your connection and try again." }
        return "Pull down to refresh once audiobooks are available on your server."
    }

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: iconName)
        } description: {
            Text(subtitle)
        } actions: {
            if lastRefreshFailed || !isOnline {
                Button("Retry", action: onRetry)
                    .buttonStyle(.bordered)
                    .tint(SonnetColors.accent)
            }
        }
    }
}

private struct LibrarySkeletonRow: View {
    var body: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(uiColor: .secondarySystemFill))
                .frame(width: 68, height: 68)

            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemFill))
                    .frame(width: 180, height: 16)

                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemFill))
                    .frame(width: 120, height: 13)

                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemFill))
                    .frame(width: 140, height: 11)
            }
            Spacer()
        }
        .padding(.vertical, 6)
        .redacted(reason: .placeholder)
    }
}

struct ErrorToastBanner: View {
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.white)
            Text(message)
                .font(.footnote.weight(.medium))
                .foregroundStyle(.white)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white.opacity(0.85))
            }
            .accessibilityLabel("Dismiss error")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(uiColor: .darkGray))
                .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        )
    }
}
