import SwiftUI
import sharedKit

struct PlayerView: View {
    let bookId: String
    let isDownloaded: Bool

    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var uiState = PlayerUiState()
    @State private var showChapters = false
    @State private var showSleepTimer = false
    @State private var scrubPosition: Double = 0
    @State private var isScrubbing = false

    var body: some View {
        Group {
            if !isDownloaded {
                ContentUnavailableView(
                    "Not Downloaded",
                    systemImage: "arrow.down.circle",
                    description: Text("This book is not downloaded yet.")
                )
            } else {
                playerContent
            }
        }
        .navigationTitle("Player")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Back") {
                    dismiss()
                }
            }
        }
        .sheet(isPresented: $showChapters) {
            ChaptersSheet(
                chapters: uiState.chapters,
                currentChapterId: uiState.currentChapterId,
                onSelect: { chapterId in
                    showChapters = false
                    appState.container.playbackHelper.jumpToChapter(chapterId: chapterId)
                }
            )
        }
        .sheet(isPresented: $showSleepTimer) {
            SleepTimerSheet(
                currentTimer: uiState.sleepTimer,
                onSelect: { timer in
                    showSleepTimer = false
                    appState.container.playbackHelper.setSleepTimer(timer: timer)
                }
            )
        }
        .alert("Resume from server?", isPresented: .constant(uiState.resumePrompt != nil)) {
            Button("Use server") {
                appState.container.playbackHelper.useRemoteProgress()
            }
            Button("Keep local", role: .cancel) {
                appState.container.playbackHelper.keepLocalProgress()
            }
        } message: {
            if let prompt = uiState.resumePrompt {
                Text("""
                Server has newer progress in "\(prompt.remoteChapterTitle)" \
                at \(formatDuration(prompt.remoteChapterOffsetMs)).
                Your local position is in "\(prompt.localChapterTitle)" \
                at \(formatDuration(prompt.localChapterOffsetMs)).
                """)
            }
        }
        .onAppear {
            appState.container.playbackHelper.load(bookId: bookId)
            appState.container.playbackHelper.observeState { state in
                DispatchQueue.main.async {
                    self.uiState = state
                }
            }
        }
    }

    private var playerContent: some View {
        VStack(spacing: 0) {
            Spacer()

            PlayerCoverView(
                title: uiState.title,
                coverFilePath: uiState.coverFilePath
            )
            .padding(.bottom, 24)

            Text(uiState.title.isEmpty ? "Loading book" : uiState.title)
                .font(.title2)
                .fontWeight(.semibold)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .padding(.bottom, 8)

            let metadata = [uiState.author, uiState.currentChapterTitle]
                .compactMap { $0 }
                .filter { !$0.isEmpty }
                .joined(separator: " \u2022 ")

            Text(metadata.isEmpty ? "Preparing chapters" : metadata)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.bottom, 24)

            VStack(spacing: 4) {
                Slider(
                    value: $scrubPosition,
                    in: 0...Double(max(uiState.currentChapterDurationMs, 1)),
                    onEditingChanged: { editing in
                        isScrubbing = editing
                        if !editing {
                            let targetMs = uiState.currentChapterStartPositionMs + Int64(scrubPosition)
                            appState.container.playbackHelper.seekToBookPosition(positionMs: targetMs)
                        }
                    }
                )

                HStack {
                    Text(formatDuration(Int64(scrubPosition)))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(formatDuration(uiState.currentChapterDurationMs))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)

            HStack(spacing: 32) {
                Button(action: { appState.container.playbackHelper.seekBack() }) {
                    Image(systemName: "gobackward.10")
                        .font(.system(size: 36))
                }
                .disabled(!uiState.canPlay)

                Button(action: { appState.container.playbackHelper.playPause() }) {
                    Image(systemName: uiState.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 72))
                }
                .disabled(!uiState.canPlay)

                Button(action: { appState.container.playbackHelper.seekForward() }) {
                    Image(systemName: "goforward.10")
                        .font(.system(size: 36))
                }
                .disabled(!uiState.canPlay)
            }
            .padding(.bottom, 24)

            HStack(spacing: 48) {
                Button(action: { showChapters = true }) {
                    VStack(spacing: 4) {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 24))
                        Text("Chapters")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(uiState.chapters.isEmpty)

                Button(action: { showSleepTimer = true }) {
                    VStack(spacing: 4) {
                        Image(systemName: "moon.zzz")
                            .font(.system(size: 24))
                        Text(sleepTimerLabel)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer()
        }
        .padding(.horizontal, 24)
        .onChange(of: uiState.currentChapterPositionMs) { _, newValue in
            if !isScrubbing {
                scrubPosition = Double(newValue)
            }
        }
        .onChange(of: uiState.currentChapterId) { _, _ in
            scrubPosition = Double(uiState.currentChapterPositionMs)
        }
    }

    private var sleepTimerLabel: String {
        if uiState.sleepTimer is SleepTimerStateOff {
            return "Sleep"
        } else if let countdown = uiState.sleepTimer as? SleepTimerStateCountdown {
            return formatDuration(countdown.remainingMs)
        } else if uiState.sleepTimer is SleepTimerStateChapterEnd {
            return "Chapter end"
        }
        return "Sleep"
    }
}

struct PlayerCoverView: View {
    let title: String
    let coverFilePath: String?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18)
                .fill(Color(.systemGray5))
                .frame(width: 220, height: 220)

            if let path = coverFilePath, let uiImage = UIImage(contentsOfFile: path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 220, height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
            } else {
                Text(title.first.map { String($0).uppercased() } ?? "?")
                    .font(.system(size: 64))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct ChaptersSheet: View {
    let chapters: [PlayerChapter]
    let currentChapterId: String?
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(chapters, id: \.id) { chapter in
                Button(action: { onSelect(chapter.id) }) {
                    HStack(spacing: 16) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(chapter.id == currentChapterId ? Color.accentColor : Color.clear)
                            .frame(width: 4, height: 44)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(chapter.title)
                                .font(.body)
                                .fontWeight(chapter.id == currentChapterId ? .semibold : .regular)
                                .foregroundStyle(chapter.id == currentChapterId ? .primary : .secondary)

                            Text(formatDuration(chapter.startPositionMs))
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

struct SleepTimerSheet: View {
    let currentTimer: SleepTimerState
    let onSelect: (SleepTimerState) -> Void

    @Environment(\.dismiss) private var dismiss

    private let options: [(String, SleepTimerState)] = [
        ("Off", SleepTimerStateOff()),
        ("15 minutes", SleepTimerStateCountdown(remainingMs: 15 * 60 * 1000)),
        ("30 minutes", SleepTimerStateCountdown(remainingMs: 30 * 60 * 1000)),
        ("60 minutes", SleepTimerStateCountdown(remainingMs: 60 * 60 * 1000)),
        ("End of chapter", SleepTimerStateChapterEnd())
    ]

    var body: some View {
        NavigationStack {
            List(options, id: \.0) { label, timer in
                Button(action: { onSelect(timer) }) {
                    HStack {
                        Text(label)
                            .foregroundStyle(.primary)
                        Spacer()
                        if isCurrentTimer(timer) {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.blue)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Sleep Timer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func isCurrentTimer(_ timer: SleepTimerState) -> Bool {
        if currentTimer is SleepTimerStateOff && timer is SleepTimerStateOff { return true }
        if currentTimer is SleepTimerStateChapterEnd && timer is SleepTimerStateChapterEnd { return true }
        if let a = currentTimer as? SleepTimerStateCountdown,
           let b = timer as? SleepTimerStateCountdown {
            return a.remainingMs == b.remainingMs
        }
        return false
    }
}

func formatDuration(_ ms: Int64) -> String {
    let totalSeconds = max(0, ms / 1000)
    let hours = totalSeconds / 3600
    let minutes = (totalSeconds % 3600) / 60
    let seconds = totalSeconds % 60
    if hours > 0 {
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }
    return String(format: "%d:%02d", minutes, seconds)
}
