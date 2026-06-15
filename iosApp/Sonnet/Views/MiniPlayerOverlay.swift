import SwiftUI
import sharedKit

struct MiniPlayerOverlay: View {
    @EnvironmentObject var appState: AppState
    @State private var uiState = PlayerUiState()

    var body: some View {
        Group {
            if let bookId = uiState.bookId, !bookId.isEmpty {
                VStack(spacing: 0) {
                    Divider()

                    Button(action: {
                        appState.openPlayer(bookId: bookId, isDownloaded: true)
                    }) {
                        HStack(spacing: 12) {
                            MiniPlayerCover(
                                title: uiState.title,
                                coverFilePath: uiState.coverFilePath
                            )

                            VStack(alignment: .leading, spacing: 2) {
                                Text(uiState.title)
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                                    .lineLimit(1)

                                Text(uiState.currentChapterTitle)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }

                            Spacer()

                            Button(action: {
                                appState.container.playbackHelper.playPause()
                            }) {
                                Image(systemName: uiState.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.title2)
                                    .foregroundStyle(.primary)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)

                    ProgressView(value: progressFraction)
                        .tint(.blue)
                }
                .background(.ultraThinMaterial)
            }
        }
        .onAppear {
            appState.container.playbackHelper.observeState { state in
                DispatchQueue.main.async {
                    self.uiState = state
                }
            }
        }
    }

    private var progressFraction: Double {
        guard uiState.durationMs > 0 else { return 0 }
        return Double(uiState.positionMs) / Double(uiState.durationMs)
    }
}

struct MiniPlayerCover: View {
    let title: String
    let coverFilePath: String?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(.systemGray5))

            if let path = coverFilePath, let uiImage = UIImage(contentsOfFile: path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            } else {
                Text(title.first.map { String($0).uppercased() } ?? "?")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 44, height: 44)
    }
}
