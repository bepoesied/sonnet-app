import SwiftUI
import sharedKit

struct MiniPlayerBar: View {
    let state: PlayerUiState
    let onOpenPlayer: () -> Void
    let onPlayPause: () -> Void

    private var chapterProgress: Double {
        let duration = max(state.currentChapterDurationMs, 1)
        let ratio = Double(state.currentChapterPositionMs) / Double(duration)
        return min(max(ratio, 0.0), 1.0)
    }

    private var subtitleText: String {
        if !state.currentChapterTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return state.currentChapterTitle
        }
        if let author = state.author, !author.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return author
        }
        return "Preparing chapters"
    }

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.35))
                    Rectangle()
                        .fill(SonnetColors.accent)
                        .frame(width: proxy.size.width * chapterProgress)
                }
            }
            .frame(height: 2.5)

            HStack(spacing: 12) {
                BookCoverView(
                    bookId: state.bookId,
                    title: state.title,
                    localCoverPath: state.coverFilePath,
                    cornerRadius: 8,
                    monogramFont: .headline,
                    crossfade: false
                )
                .frame(width: 50, height: 50)

                VStack(alignment: .leading, spacing: 2) {
                    Text(state.title.isEmpty ? "Loading book" : state.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)

                    Text(subtitleText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: onPlayPause) {
                    Image(systemName: state.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .foregroundStyle(.primary)
                        .frame(width: 44, height: 44)
                }
                .disabled(!state.canPlay)
                .accessibilityLabel(state.isPlaying ? "Pause" : "Play")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpenPlayer)
        }
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Divider()
        }
    }
}
