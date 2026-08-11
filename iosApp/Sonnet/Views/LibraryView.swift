import SwiftUI
import sharedKit

struct LibraryView: View {
    @EnvironmentObject var appState: AppState
    @State private var books: [LibraryBook] = []
    @State private var isRefreshing = false
    @State private var errorMessage: String? = nil
    @State private var initialLoadComplete = false

    var body: some View {
        Group {
            if !initialLoadComplete {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if books.isEmpty && isRefreshing {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if books.isEmpty {
                ContentUnavailableView(
                    "No books yet",
                    systemImage: "book.closed",
                    description: Text("Pull to refresh or download books from your Sonnet server.")
                )
            } else {
                List {
                    ForEach(books, id: \.id) { book in
                        LibraryRow(book: book)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if book.isDownloaded {
                                    appState.openPlayer(bookId: book.id, isDownloaded: true)
                                }
                            }
                            .swipeActions(edge: .leading) {
                                Button {
                                    appState.container.libraryHelper.swipeCompletionAction(book: book)
                                } label: {
                                    Label(
                                        book.isCompleted ? "Unplayed" : "Played",
                                        systemImage: book.isCompleted ? "arrow.uturn.backward" : "checkmark"
                                    )
                                }
                                .tint(book.isCompleted ? .orange : .green)
                            }
                            .swipeActions(edge: .trailing) {
                                Button {
                                    appState.container.libraryHelper.swipeDownloadAction(book: book)
                                } label: {
                                    Label(
                                        downloadActionLabel(for: book),
                                        systemImage: downloadActionIcon(for: book)
                                    )
                                }
                                .tint(downloadActionTint(for: book))
                            }
                    }
                }
                .listStyle(.plain)
                .refreshable {
                    appState.container.libraryHelper.refresh()
                }
            }
        }
        .navigationTitle("Library")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Log out") {
                    appState.logout()
                }
            }
        }
        .alert("Error", isPresented: .constant(errorMessage != nil)) {
            Button("OK") {
                errorMessage = nil
                appState.container.libraryHelper.clearError()
            }
        } message: {
            Text(errorMessage ?? "")
        }
        .onAppear {
            appState.container.libraryHelper.observeUiState { state in
                DispatchQueue.main.async {
                    self.books = state.books
                    self.isRefreshing = state.isRefreshing
                    self.initialLoadComplete = state.initialLoadComplete
                    if let msg = state.errorMessage {
                        self.errorMessage = msg
                    }
                }
            }
        }
    }

    private func downloadActionLabel(for book: LibraryBook) -> String {
        switch book.downloadStatus {
        case .notdownloaded: return "Download"
        case .failed: return "Retry"
        case .queued, .downloading: return "Cancel"
        case .downloaded: return "Delete"
        @unknown default: return "Download"
        }
    }

    private func downloadActionIcon(for book: LibraryBook) -> String {
        switch book.downloadStatus {
        case .notdownloaded, .failed: return "arrow.down.circle"
        case .queued, .downloading: return "xmark.circle"
        case .downloaded: return "trash"
        @unknown default: return "arrow.down.circle"
        }
    }

    private func downloadActionTint(for book: LibraryBook) -> Color {
        switch book.downloadStatus {
        case .notdownloaded, .failed: return .blue
        case .queued, .downloading, .downloaded: return .red
        @unknown default: return .blue
        }
    }
}

struct LibraryRow: View {
    let book: LibraryBook

    var body: some View {
        HStack(spacing: 16) {
            CoverImageView(
                localPath: book.localCoverUri,
                remoteUrl: book.remoteCoverUrl,
                title: book.title,
                size: 72
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(book.title)
                    .font(.headline)
                    .lineLimit(1)

                if let author = book.author, !author.isEmpty {
                    Text(author)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                HStack(spacing: 12) {
                    Text(book.isCompleted ? "Completed" : "In progress")
                        .font(.caption)
                        .foregroundStyle(book.isCompleted ? .blue : .secondary)

                    Text(downloadStatusLabel(book.downloadStatus))
                        .font(.caption)
                        .foregroundStyle(downloadStatusColor(book.downloadStatus))
                }

                if book.downloadStatus == .downloading {
                    ProgressView(value: downloadProgress(book))
                        .padding(.top, 4)
                }

                if !book.isDownloaded {
                    Text("Swipe left to \(downloadActionVerb(book.downloadStatus))")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .padding(.top, 2)
                }
            }
        }
        .padding(.vertical, 8)
    }

    private func downloadStatusLabel(_ status: DownloadStatus) -> String {
        switch status {
        case .notdownloaded: return "Not downloaded"
        case .queued: return "Queued"
        case .downloading: return "Downloading"
        case .downloaded: return "Downloaded"
        case .failed: return "Failed"
        @unknown default: return ""
        }
    }

    private func downloadStatusColor(_ status: DownloadStatus) -> Color {
        switch status {
        case .notdownloaded: return .secondary
        case .queued: return .orange
        case .downloading: return .blue
        case .downloaded: return .green
        case .failed: return .red
        @unknown default: return .secondary
        }
    }

    private func downloadActionVerb(_ status: DownloadStatus) -> String {
        switch status {
        case .notdownloaded, .failed: return "download"
        case .queued, .downloading: return "cancel"
        case .downloaded: return "delete"
        @unknown default: return "download"
        }
    }

    private func downloadProgress(_ book: LibraryBook) -> Double {
        guard let total = book.totalChapters, total > 0 else {
            if let percent = book.progressPercent {
                return Double(truncating: percent) / 100.0
            }
            return 0
        }
        return Double(book.downloadedChapters) / Double(total)
    }
}

struct CoverImageView: View {
    let localPath: String?
    let remoteUrl: String?
    let title: String
    let size: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(.systemGray5))

            if let path = localPath, let uiImage = UIImage(contentsOfFile: path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: size, height: size)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else if let urlString = remoteUrl, let url = URL(string: urlString) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    case .failure:
                        placeholderInitial
                    case .empty:
                        ProgressView()
                    @unknown default:
                        placeholderInitial
                    }
                }
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                placeholderInitial
            }
        }
        .frame(width: size, height: size)
    }

    private var placeholderInitial: some View {
        Text(title.first.map { String($0).uppercased() } ?? "?")
            .font(.title2)
            .foregroundStyle(.secondary)
    }
}
