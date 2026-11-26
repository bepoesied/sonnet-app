import SwiftUI
import sharedKit

struct PlayerView: View {
    private let bookId: String
    private let isDownloaded: Bool
    private let container: IosAppContainer
    private let onDismiss: () -> Void

    @State private var store: PlayerStore?

    init(
        bookId: String,
        isDownloaded: Bool,
        container: IosAppContainer,
        onDismiss: @escaping () -> Void
    ) {
        self.bookId = bookId
        self.isDownloaded = isDownloaded
        self.container = container
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            Group {
                if !isDownloaded {
                    BookUnavailableView(
                        message: "This book is not downloaded yet.",
                        onBack: onDismiss
                    )
                } else if let store {
                    PlayerScreenContent(
                        bookId: bookId,
                        store: store,
                        onDismiss: onDismiss
                    )
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(Color(uiColor: .systemBackground))
            .navigationTitle("Player")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onDismiss) {
                        Image(systemName: "chevron.down")
                            .fontWeight(.semibold)
                    }
                    .accessibilityLabel("Minimize player")
                }
            }
        }
        .task(id: bookId) {
            guard isDownloaded else { return }
            if store?.bookId != bookId {
                let newStore = PlayerStore(container: container, bookId: bookId)
                store = newStore
                newStore.loadBook()
            } else {
                store?.loadBook()
            }
        }
    }
}

private struct PlayerScreenContent: View {
    let bookId: String
    let store: PlayerStore
    let onDismiss: () -> Void

    @State private var showChaptersSheet = false
    @State private var showSleepTimerSheet = false

    var body: some View {
        ZStack(alignment: .bottom) {
            Group {
                if store.isUnavailable {
                    BookUnavailableView(
                        message: store.uiState.errorMessage
                            ?? "This book is unavailable. Try downloading it again.",
                        onBack: onDismiss
                    )
                } else {
                    playerBody
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if !store.isUnavailable,
               let errorMessage = store.uiState.errorMessage,
               !errorMessage.isEmpty {
                ErrorToastBanner(message: errorMessage) {
                    store.clearError()
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: errorMessage) {
                    try? await Task.sleep(for: .seconds(4))
                    if store.uiState.errorMessage == errorMessage {
                        store.clearError()
                    }
                }
            }
        }
        .sheet(isPresented: $showChaptersSheet) {
            ChapterSheetView(
                chapters: store.uiState.chapters,
                currentChapterId: store.uiState.currentChapterId,
                onSelectChapter: { chapterId in
                    showChaptersSheet = false
                    store.jumpToChapter(chapterId: chapterId)
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSleepTimerSheet) {
            SleepTimerSheetView(
                currentTimer: store.uiState.sleepTimer,
                onSelectTimer: { timer in
                    showSleepTimerSheet = false
                    store.setSleepTimer(timer)
                }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .alert(
            "Resume from server?",
            isPresented: Binding(
                get: { store.uiState.resumePrompt != nil },
                set: { _ in }
            ),
            presenting: store.uiState.resumePrompt
        ) { _ in
            Button("Keep local", role: .cancel) {
                store.keepLocalProgress()
            }
            Button("Use server") {
                store.useRemoteProgress()
            }
        } message: { prompt in
            if prompt.remoteIsCompleted {
                Text(
                    "This book is marked finished on the server.\nYour local position is in \"\(prompt.localChapterTitle)\" at \(DurationFormatting.formatDuration(ms: prompt.localChapterOffsetMs))."
                )
            } else {
                Text(
                    "Server has newer progress in \"\(prompt.remoteChapterTitle)\" at \(DurationFormatting.formatDuration(ms: prompt.remoteChapterOffsetMs)).\nYour local position is in \"\(prompt.localChapterTitle)\" at \(DurationFormatting.formatDuration(ms: prompt.localChapterOffsetMs))."
                )
            }
        }
    }

    private var playerBody: some View {
        let uiState = store.uiState

        return ScrollView {
            VStack(spacing: 24) {
                BookCoverView(
                    bookId: uiState.bookId ?? bookId,
                    title: uiState.title,
                    localCoverPath: uiState.coverFilePath,
                    cornerRadius: 18,
                    monogramFont: .system(size: 56, weight: .semibold),
                    crossfade: false
                )
                .frame(width: 240, height: 240)
                .shadow(color: .black.opacity(0.18), radius: 16, y: 8)
                .padding(.top, 12)

                VStack(spacing: 8) {
                    Text(uiState.title.isEmpty ? "Loading book" : uiState.title)
                        .font(.title2.weight(.bold))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)

                    Text(subtitleText(for: uiState))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                }
                .padding(.horizontal, 8)

                PlayerSeekBar(
                    chapterId: uiState.currentChapterId,
                    chapterStartPositionMs: uiState.currentChapterStartPositionMs,
                    chapterPositionMs: uiState.currentChapterPositionMs,
                    chapterDurationMs: uiState.currentChapterDurationMs,
                    canPlay: uiState.canPlay,
                    onSeekTo: { positionMs in
                        store.seekTo(positionMs: positionMs)
                    }
                )

                HStack(spacing: 32) {
                    Button {
                        store.seekBack()
                    } label: {
                        Image(systemName: "gobackward.10")
                            .font(.system(size: 30, weight: .medium))
                            .frame(width: 64, height: 64)
                            .background(
                                Circle()
                                    .fill(Color(uiColor: .secondarySystemFill))
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!uiState.canPlay)
                    .opacity(uiState.canPlay ? 1.0 : 0.45)
                    .accessibilityLabel("Seek back 10 seconds")

                    Button {
                        store.playPause()
                    } label: {
                        Image(systemName: uiState.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 36, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 84, height: 84)
                            .background(
                                Circle()
                                    .fill(SonnetColors.accent)
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!uiState.canPlay)
                    .opacity(uiState.canPlay ? 1.0 : 0.45)
                    .accessibilityLabel(uiState.isPlaying ? "Pause" : "Play")

                    Button {
                        store.seekForward()
                    } label: {
                        Image(systemName: "goforward.10")
                            .font(.system(size: 30, weight: .medium))
                            .frame(width: 64, height: 64)
                            .background(
                                Circle()
                                    .fill(Color(uiColor: .secondarySystemFill))
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!uiState.canPlay)
                    .opacity(uiState.canPlay ? 1.0 : 0.45)
                    .accessibilityLabel("Seek forward 10 seconds")
                }
                .padding(.vertical, 4)

                HStack(spacing: 48) {
                    Button {
                        showChaptersSheet = true
                    } label: {
                        VStack(spacing: 6) {
                            Image(systemName: "list.bullet")
                                .font(.title2)
                                .frame(width: 48, height: 48)
                            Text("Chapters")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(uiState.chapters.isEmpty)
                    .opacity(uiState.chapters.isEmpty ? 0.45 : 1.0)

                    let sleepActive = uiState.sleepTimer.isActive
                    Button {
                        showSleepTimerSheet = true
                    } label: {
                        VStack(spacing: 6) {
                            Image(systemName: "timer")
                                .font(.title2)
                                .foregroundStyle(sleepActive ? SonnetColors.accent : .primary)
                                .frame(width: 48, height: 48)
                            Text(sleepActive ? uiState.sleepTimer.displayLabel : "Sleep")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(sleepActive ? SonnetColors.accent : .secondary)
                                .monospacedDigit()
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(sleepActive ? uiState.sleepTimer.displayLabel : "Sleep timer")
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
    }

    private func subtitleText(for state: PlayerUiState) -> String {
        let parts = [
            state.author?.trimmingCharacters(in: .whitespacesAndNewlines),
            state.currentChapterTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        ].compactMap { part in
            if let part, !part.isEmpty { return part }
            return nil
        }
        return parts.isEmpty ? "Preparing chapters" : parts.joined(separator: " • ")
    }
}

private struct PlayerSeekBar: View {
    let chapterId: String?
    let chapterStartPositionMs: Int64
    let chapterPositionMs: Int64
    let chapterDurationMs: Int64
    let canPlay: Bool
    let onSeekTo: (Int64) -> Void

    @State private var dragPositionMs: Int64?
    @State private var pendingSeekPositionMs: Int64?

    private var safeDurationMs: Int64 {
        max(chapterDurationMs, 1)
    }

    private var displayedPositionMs: Int64 {
        let activePosition = dragPositionMs ?? pendingSeekPositionMs ?? chapterPositionMs
        return min(max(activePosition, 0), safeDurationMs)
    }

    var body: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: { Double(displayedPositionMs) },
                    set: { newValue in
                        let clamped = min(max(Int64(newValue.rounded()), 0), safeDurationMs)
                        dragPositionMs = clamped
                    }
                ),
                in: 0...Double(safeDurationMs),
                onEditingChanged: { isEditing in
                    if !isEditing, let target = dragPositionMs {
                        pendingSeekPositionMs = target
                        dragPositionMs = nil
                        onSeekTo(chapterStartPositionMs + target)
                    }
                }
            )
            .disabled(!canPlay)
            .tint(SonnetColors.accent)
            .accessibilityLabel("Playback position")

            HStack {
                Text(DurationFormatting.formatDuration(ms: displayedPositionMs))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()

                Spacer()

                Text(DurationFormatting.formatDuration(ms: chapterDurationMs))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
        .onChange(of: chapterId) { _, _ in
            dragPositionMs = nil
            pendingSeekPositionMs = nil
        }
        .onChange(of: chapterPositionMs) { _, _ in
            if pendingSeekPositionMs != nil {
                pendingSeekPositionMs = nil
            }
        }
        .task(id: pendingSeekPositionMs) {
            guard pendingSeekPositionMs != nil else { return }
            try? await Task.sleep(for: .milliseconds(750))
            pendingSeekPositionMs = nil
        }
    }
}

private struct BookUnavailableView: View {
    let message: String
    let onBack: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("Unavailable", systemImage: "exclamationmark.circle")
        } description: {
            Text(message)
        } actions: {
            Button("Back to library", action: onBack)
                .buttonStyle(.bordered)
                .tint(SonnetColors.accent)
        }
    }
}

private struct ChapterSheetView: View {
    let chapters: [PlayerChapter]
    let currentChapterId: String?
    let onSelectChapter: (String) -> Void

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    ForEach(chapters, id: \.id) { chapter in
                        let selected = chapter.id == currentChapterId
                        Button {
                            onSelectChapter(chapter.id)
                        } label: {
                            HStack(spacing: 12) {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(selected ? SonnetColors.accent : .clear)
                                    .frame(width: 4, height: 36)

                                VStack(alignment: .leading, spacing: 3) {
                                    Text(chapter.title)
                                        .font(.body.weight(selected ? .semibold : .regular))
                                        .foregroundStyle(selected ? SonnetColors.accent : .primary)
                                        .lineLimit(2)

                                    Text(DurationFormatting.formatDuration(ms: chapter.startPositionMs))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .monospacedDigit()
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)

                                if selected {
                                    Image(systemName: "checkmark")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(SonnetColors.accent)
                                        .accessibilityLabel("Current chapter")
                                }
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .id(chapter.id)
                    }
                }
                .listStyle(.plain)
                .onAppear {
                    if let currentChapterId {
                        proxy.scrollTo(currentChapterId, anchor: .center)
                    }
                }
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct SleepTimerSheetView: View {
    let currentTimer: SleepTimerState
    let onSelectTimer: (SleepTimerState) -> Void

    private struct PresetOption: Identifiable {
        let id: String
        let label: String
        let state: SleepTimerState
    }

    private let options: [PresetOption] = [
        PresetOption(id: "off", label: "Off", state: SleepTimerStateOff.shared),
        PresetOption(id: "chapter_end", label: "End of chapter", state: SleepTimerStateChapterEnd.shared),
        PresetOption(id: "15m", label: "15 minutes", state: SleepTimerStateCountdown(remainingMs: 15 * 60_000)),
        PresetOption(id: "30m", label: "30 minutes", state: SleepTimerStateCountdown(remainingMs: 30 * 60_000)),
        PresetOption(id: "60m", label: "60 minutes", state: SleepTimerStateCountdown(remainingMs: 60 * 60_000))
    ]

    var body: some View {
        NavigationStack {
            List {
                if let countdown = currentTimer as? SleepTimerStateCountdown {
                    Section {
                        HStack(spacing: 12) {
                            Image(systemName: "timer")
                                .foregroundStyle(SonnetColors.onAccentContainer)
                            Text("Active: \(DurationFormatting.formatSleepTimerCountdown(remainingMs: countdown.remainingMs)) remaining")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(SonnetColors.onAccentContainer)
                                .monospacedDigit()
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "checkmark")
                                .foregroundStyle(SonnetColors.onAccentContainer)
                                .accessibilityLabel("Active sleep timer")
                        }
                        .padding(.vertical, 4)
                        .listRowBackground(SonnetColors.accentContainer)
                    }
                }

                Section {
                    ForEach(options) { option in
                        let selected = isOptionSelected(option.state)
                        Button {
                            onSelectTimer(option.state)
                        } label: {
                            HStack {
                                Text(option.label)
                                    .font(.body.weight(selected ? .semibold : .regular))
                                    .foregroundStyle(selected ? SonnetColors.accent : .primary)
                                Spacer()
                                if selected {
                                    Image(systemName: "checkmark")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(SonnetColors.accent)
                                        .accessibilityLabel("Selected")
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Sleep timer")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func isOptionSelected(_ optionState: SleepTimerState) -> Bool {
        if optionState is SleepTimerStateOff {
            return currentTimer is SleepTimerStateOff
        }
        if optionState is SleepTimerStateChapterEnd {
            return currentTimer is SleepTimerStateChapterEnd
        }
        if let optionCountdown = optionState as? SleepTimerStateCountdown,
           let currentCountdown = currentTimer as? SleepTimerStateCountdown {
            return optionCountdown.remainingMs == currentCountdown.remainingMs
        }
        return false
    }
}
