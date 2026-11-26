import AVFoundation
import Foundation
import MediaPlayer
import UIKit
import sharedKit

/// Native iOS audiobook playback driver backed by `AVPlayer`, `AVAudioSession`,
/// `MPNowPlayingInfoCenter`, and `MPRemoteCommandCenter`.
final class AVFoundationPlaybackDriver: NSObject, PlaybackPlatformDriver {
    private var callback: PlaybackDriverCallback?
    private var player: AVPlayer?
    private var items: [MediaItemDescriptor] = []
    private var currentIndex: Int = 0
    private var connected: Bool = false
    private var wasPlayingBeforeInterruption: Bool = false
    private var cachedArtworkPath: String?
    private var cachedArtwork: MPMediaItemArtwork?

    private var timeControlStatusObserver: NSKeyValueObservation?
    private var itemStatusObserver: NSKeyValueObservation?
    private var itemDurationObserver: NSKeyValueObservation?
    private var didPlayToEndObserver: NSObjectProtocol?
    private var failedToPlayToEndObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?
    private var remoteCommandsConfigured: Bool = false

    private let seekIncrementSeconds: Double =
        Double(DelegatedPlaybackEngine.companion.DEFAULT_SEEK_INCREMENT_MS) / 1000.0

    func attachCallback(callback: PlaybackDriverCallback?) {
        runOnMain { [weak self] in
            self?.callback = callback
        }
    }

    func connect() -> Bool {
        runOnMainSync {
            if connected && player != nil {
                return true
            }

            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .spokenAudio)
                try session.setActive(true)
            } catch {
                callback?.onPlayerError(message: "Unable to configure audio session: \(error.localizedDescription)")
                return false
            }

            let avPlayer = AVPlayer()
            avPlayer.automaticallyWaitsToMinimizeStalling = false
            self.player = avPlayer
            self.connected = true

            observePlayer(avPlayer)
            registerAudioSessionNotifications()
            configureRemoteCommands()
            return true
        }
    }

    func disconnect() {
        runOnMain { [weak self] in
            self?.teardownOnMain()
        }
    }

    func loadMedia(items: [MediaItemDescriptor], startIndex: Int32, startPositionMs: Int64) {
        runOnMain { [weak self] in
            guard let self else { return }
            self.items = items
            guard !items.isEmpty else {
                self.currentIndex = 0
                self.clearCurrentItemObservers()
                self.player?.replaceCurrentItem(with: nil)
                self.clearNowPlayingInfo()
                return
            }

            let clampedIndex = min(max(Int(startIndex), 0), items.count - 1)
            self.loadChapterOnMain(
                index: clampedIndex,
                positionMs: max(startPositionMs, 0),
                autoPlay: false
            )
        }
    }

    func prepare() {
        // AVPlayer prepares automatically when replacing the current AVPlayerItem.
    }

    func play() {
        runOnMain { [weak self] in
            guard let self, let player = self.player, !self.items.isEmpty else { return }
            try? AVAudioSession.sharedInstance().setActive(true)
            player.play()
            self.updateNowPlayingInfo(isPlayingOverride: true)
        }
    }

    func pause() {
        runOnMain { [weak self] in
            guard let self, let player = self.player else { return }
            player.pause()
            self.updateNowPlayingInfo(isPlayingOverride: false)
        }
    }

    func seekBack() {
        runOnMain { [weak self] in
            guard let self, self.player != nil, !self.items.isEmpty else { return }
            let wasPlaying = self.isPlayingOnMain()
            let currentSeconds = self.currentPositionSecondsOnMain()
            let targetSeconds = currentSeconds - self.seekIncrementSeconds

            if targetSeconds >= 0 {
                self.seekWithinCurrentChapterOnMain(toSeconds: targetSeconds)
            } else if self.currentIndex > 0 {
                let previousIndex = self.currentIndex - 1
                self.loadChapterOnMain(
                    index: previousIndex,
                    positionMs: 0,
                    autoPlay: wasPlaying,
                    seekFromEndBySeconds: abs(targetSeconds)
                )
                self.callback?.onMediaItemTransition(
                    index: Int32(previousIndex),
                    reason: DelegatedPlaybackEngine.companion.MEDIA_ITEM_TRANSITION_REASON_SEEK
                )
            } else {
                self.seekWithinCurrentChapterOnMain(toSeconds: 0)
            }
        }
    }

    func seekForward() {
        runOnMain { [weak self] in
            guard let self, self.player != nil, !self.items.isEmpty else { return }
            let wasPlaying = self.isPlayingOnMain()
            let currentSeconds = self.currentPositionSecondsOnMain()
            let durationSeconds = self.currentDurationSecondsOnMain()
            let targetSeconds = currentSeconds + self.seekIncrementSeconds

            if let durationSeconds, durationSeconds > 0, targetSeconds >= durationSeconds {
                if self.currentIndex + 1 < self.items.count {
                    let nextIndex = self.currentIndex + 1
                    let overflowMs = Int64(max(targetSeconds - durationSeconds, 0) * 1000.0)
                    self.loadChapterOnMain(index: nextIndex, positionMs: overflowMs, autoPlay: wasPlaying)
                    self.callback?.onMediaItemTransition(
                        index: Int32(nextIndex),
                        reason: DelegatedPlaybackEngine.companion.MEDIA_ITEM_TRANSITION_REASON_SEEK
                    )
                } else {
                    self.seekWithinCurrentChapterOnMain(toSeconds: durationSeconds)
                }
            } else {
                self.seekWithinCurrentChapterOnMain(toSeconds: targetSeconds)
            }
        }
    }

    func seekToChapter(chapterIndex: Int32, positionMs: Int64) {
        runOnMain { [weak self] in
            guard let self, !self.items.isEmpty else { return }
            let clampedIndex = min(max(Int(chapterIndex), 0), self.items.count - 1)
            let clampedPositionMs = max(positionMs, 0)

            if clampedIndex == self.currentIndex, self.player?.currentItem != nil {
                self.seekWithinCurrentChapterOnMain(toSeconds: Double(clampedPositionMs) / 1000.0)
            } else {
                let wasPlaying = self.isPlayingOnMain()
                self.loadChapterOnMain(index: clampedIndex, positionMs: clampedPositionMs, autoPlay: wasPlaying)
                self.callback?.onMediaItemTransition(
                    index: Int32(clampedIndex),
                    reason: DelegatedPlaybackEngine.companion.MEDIA_ITEM_TRANSITION_REASON_SEEK
                )
                self.callback?.onPositionDiscontinuity()
            }
        }
    }

    func currentPositionMs() -> Int64 {
        runOnMainSync {
            Int64(max(currentPositionSecondsOnMain(), 0) * 1000.0)
        }
    }

    func currentMediaItemIndex() -> Int32 {
        runOnMainSync {
            Int32(currentIndex)
        }
    }

    func isPlaying() -> Bool {
        runOnMainSync {
            isPlayingOnMain()
        }
    }

    func mediaItemCount() -> Int32 {
        runOnMainSync {
            Int32(items.count)
        }
    }

    deinit {
        let center = NotificationCenter.default
        if let observer = didPlayToEndObserver { center.removeObserver(observer) }
        if let observer = failedToPlayToEndObserver { center.removeObserver(observer) }
        if let observer = interruptionObserver { center.removeObserver(observer) }
        if let observer = routeChangeObserver { center.removeObserver(observer) }
        timeControlStatusObserver?.invalidate()
        itemStatusObserver?.invalidate()
        itemDurationObserver?.invalidate()
    }

    // MARK: - Internal Main-Thread Helpers

    private func teardownOnMain() {
        clearCurrentItemObservers()
        timeControlStatusObserver?.invalidate()
        timeControlStatusObserver = nil
        removeAudioSessionNotifications()
        teardownRemoteCommands()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        items = []
        currentIndex = 0
        connected = false
        wasPlayingBeforeInterruption = false
        cachedArtworkPath = nil
        cachedArtwork = nil
        clearNowPlayingInfo()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func loadChapterOnMain(
        index: Int,
        positionMs: Int64,
        autoPlay: Bool,
        seekFromEndBySeconds: Double? = nil
    ) {
        guard let player, items.indices.contains(index) else { return }
        clearCurrentItemObservers()

        currentIndex = index
        let descriptor = items[index]
        let url = resolveLocalURL(descriptor.uri)
        let asset = AVURLAsset(url: url)
        let playerItem = AVPlayerItem(asset: asset)

        observeCurrentItem(playerItem)
        player.replaceCurrentItem(with: playerItem)

        if let seekFromEndBySeconds {
            Task { @MainActor [weak self, weak playerItem] in
                guard let self, let playerItem, self.player?.currentItem === playerItem else { return }
                let duration = try? await asset.load(.duration)
                let durationSeconds = duration.map(CMTimeGetSeconds) ?? 0
                let targetSeconds = durationSeconds.isFinite ? max(durationSeconds - seekFromEndBySeconds, 0) : 0
                self.seekWithinCurrentChapterOnMain(toSeconds: targetSeconds)
                if autoPlay {
                    self.player?.play()
                }
            }
        } else {
            if positionMs > 0 {
                let target = CMTime(value: positionMs, timescale: 1000)
                player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                    self?.runOnMain {
                        guard let self else { return }
                        self.updateNowPlayingInfo()
                        self.callback?.onPositionDiscontinuity()
                    }
                }
            }
            if autoPlay {
                player.play()
            }
        }

        updateNowPlayingInfo(isPlayingOverride: autoPlay)
    }

    private func seekWithinCurrentChapterOnMain(toSeconds seconds: Double) {
        guard let player else { return }
        let clampedSeconds = max(seconds, 0)
        let target = CMTime(seconds: clampedSeconds, preferredTimescale: 1000)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            self?.runOnMain {
                guard let self else { return }
                self.updateNowPlayingInfo()
                self.callback?.onPositionDiscontinuity()
            }
        }
    }

    private func observePlayer(_ player: AVPlayer) {
        timeControlStatusObserver?.invalidate()
        timeControlStatusObserver = player.observe(\.timeControlStatus, options: [.new, .old]) { [weak self] observedPlayer, change in
            guard change.newValue != change.oldValue else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.player === observedPlayer else { return }
                let playing = observedPlayer.timeControlStatus == .playing
                let playbackState: Int32 = switch observedPlayer.timeControlStatus {
                case .waitingToPlayAtSpecifiedRate:
                    DelegatedPlaybackEngine.companion.PLAYBACK_STATE_BUFFERING
                case .playing, .paused:
                    DelegatedPlaybackEngine.companion.PLAYBACK_STATE_READY
                @unknown default:
                    DelegatedPlaybackEngine.companion.PLAYBACK_STATE_READY
                }
                self.updateNowPlayingInfo(isPlayingOverride: playing)
                self.callback?.onPlaybackStateChanged(isPlaying: playing, playbackState: playbackState)
            }
        }
    }

    private func observeCurrentItem(_ item: AVPlayerItem) {
        itemStatusObserver = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            DispatchQueue.main.async { [weak self] in
                guard let self, self.player?.currentItem === observedItem else { return }
                switch observedItem.status {
                case .readyToPlay:
                    self.updateNowPlayingInfo()
                case .failed:
                    let message = observedItem.error?.localizedDescription ?? "Playback failed."
                    self.callback?.onPlayerError(message: message)
                case .unknown:
                    break
                @unknown default:
                    break
                }
            }
        }

        itemDurationObserver = item.observe(\.duration, options: [.new]) { [weak self] observedItem, _ in
            DispatchQueue.main.async { [weak self] in
                guard let self, self.player?.currentItem === observedItem else { return }
                self.updateNowPlayingInfo()
            }
        }

        didPlayToEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            self?.handleChapterDidFinishOnMain()
        }

        failedToPlayToEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            self?.callback?.onPlayerError(message: error?.localizedDescription ?? "Playback failed.")
        }
    }

    private func clearCurrentItemObservers() {
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        itemDurationObserver?.invalidate()
        itemDurationObserver = nil
        if let observer = didPlayToEndObserver {
            NotificationCenter.default.removeObserver(observer)
            didPlayToEndObserver = nil
        }
        if let observer = failedToPlayToEndObserver {
            NotificationCenter.default.removeObserver(observer)
            failedToPlayToEndObserver = nil
        }
    }

    private func handleChapterDidFinishOnMain() {
        let nextIndex = currentIndex + 1
        if nextIndex < items.count {
            loadChapterOnMain(index: nextIndex, positionMs: 0, autoPlay: true)
            callback?.onMediaItemTransition(
                index: Int32(nextIndex),
                reason: DelegatedPlaybackEngine.companion.MEDIA_ITEM_TRANSITION_REASON_AUTO
            )
        } else {
            player?.pause()
            updateNowPlayingInfo(isPlayingOverride: false)
            callback?.onPlaybackStateChanged(
                isPlaying: false,
                playbackState: DelegatedPlaybackEngine.companion.PLAYBACK_STATE_ENDED
            )
        }
    }

    // MARK: - Audio Session Notifications

    private func registerAudioSessionNotifications() {
        removeAudioSessionNotifications()
        let center = NotificationCenter.default

        interruptionObserver = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            self?.handleAudioInterruptionOnMain(notification)
        }

        routeChangeObserver = center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            self?.handleAudioRouteChangeOnMain(notification)
        }
    }

    private func removeAudioSessionNotifications() {
        let center = NotificationCenter.default
        if let observer = interruptionObserver {
            center.removeObserver(observer)
            interruptionObserver = nil
        }
        if let observer = routeChangeObserver {
            center.removeObserver(observer)
            routeChangeObserver = nil
        }
    }

    private func handleAudioInterruptionOnMain(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            wasPlayingBeforeInterruption = isPlayingOnMain()
            if wasPlayingBeforeInterruption {
                player?.pause()
            }
        case .ended:
            defer { wasPlayingBeforeInterruption = false }
            guard wasPlayingBeforeInterruption,
                  let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else {
                return
            }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            if options.contains(.shouldResume) {
                try? AVAudioSession.sharedInstance().setActive(true)
                player?.play()
            }
        @unknown default:
            break
        }
    }

    private func handleAudioRouteChangeOnMain(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        if reason == .oldDeviceUnavailable && isPlayingOnMain() {
            player?.pause()
        }
    }

    // MARK: - Lock Screen & Control Center

    private func configureRemoteCommands() {
        guard !remoteCommandsConfigured else { return }
        remoteCommandsConfigured = true
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self else { return .noActionableNowPlayingItem }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                self.play()
                return .success
            }
        }

        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard let self else { return .noActionableNowPlayingItem }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                self.pause()
                return .success
            }
        }

        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .noActionableNowPlayingItem }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                if self.isPlayingOnMain() {
                    self.pause()
                } else {
                    self.play()
                }
                return .success
            }
        }

        commandCenter.skipBackwardCommand.isEnabled = true
        commandCenter.skipBackwardCommand.preferredIntervals = [NSNumber(value: seekIncrementSeconds)]
        commandCenter.skipBackwardCommand.addTarget { [weak self] _ in
            guard let self else { return .noActionableNowPlayingItem }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                self.seekBack()
                return .success
            }
        }

        commandCenter.skipForwardCommand.isEnabled = true
        commandCenter.skipForwardCommand.preferredIntervals = [NSNumber(value: seekIncrementSeconds)]
        commandCenter.skipForwardCommand.addTarget { [weak self] _ in
            guard let self else { return .noActionableNowPlayingItem }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                self.seekForward()
                return .success
            }
        }

        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self,
                  let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                self.seekWithinCurrentChapterOnMain(toSeconds: positionEvent.positionTime)
                return .success
            }
        }

        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let self else { return .noSuchContent }
            return self.runOnMainSync {
                guard self.currentIndex + 1 < self.items.count else {
                    return .noSuchContent
                }
                self.seekToChapter(chapterIndex: Int32(self.currentIndex + 1), positionMs: 0)
                return .success
            }
        }

        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let self else { return .noActionableNowPlayingItem }
            return self.runOnMainSync {
                guard !self.items.isEmpty else { return .noActionableNowPlayingItem }
                if self.currentPositionSecondsOnMain() > 3.0 || self.currentIndex == 0 {
                    self.seekWithinCurrentChapterOnMain(toSeconds: 0)
                } else {
                    self.seekToChapter(chapterIndex: Int32(self.currentIndex - 1), positionMs: 0)
                }
                return .success
            }
        }
    }

    private func teardownRemoteCommands() {
        guard remoteCommandsConfigured else { return }
        remoteCommandsConfigured = false
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.removeTarget(nil)
        commandCenter.pauseCommand.removeTarget(nil)
        commandCenter.togglePlayPauseCommand.removeTarget(nil)
        commandCenter.skipBackwardCommand.removeTarget(nil)
        commandCenter.skipForwardCommand.removeTarget(nil)
        commandCenter.changePlaybackPositionCommand.removeTarget(nil)
        commandCenter.nextTrackCommand.removeTarget(nil)
        commandCenter.previousTrackCommand.removeTarget(nil)
    }

    private func updateNowPlayingInfo(isPlayingOverride: Bool? = nil) {
        guard items.indices.contains(currentIndex) else {
            clearNowPlayingInfo()
            return
        }

        let item = items[currentIndex]
        let playing = isPlayingOverride ?? isPlayingOnMain()
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.title,
            MPMediaItemPropertyAlbumTitle: item.albumTitle,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentPositionSecondsOnMain(),
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyPlaybackQueueIndex: currentIndex,
            MPNowPlayingInfoPropertyPlaybackQueueCount: items.count
        ]

        if let artist = item.artist, !artist.isEmpty {
            info[MPMediaItemPropertyArtist] = artist
        }

        if let durationSeconds = currentDurationSecondsOnMain(), durationSeconds > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = durationSeconds
        }

        if let artwork = loadArtworkOnMain(path: item.artworkUri) {
            info[MPMediaItemPropertyArtwork] = artwork
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func clearNowPlayingInfo() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func loadArtworkOnMain(path: String?) -> MPMediaItemArtwork? {
        guard let path, !path.isEmpty else { return nil }
        if path == cachedArtworkPath, let cachedArtwork {
            return cachedArtwork
        }
        let localURL = resolveLocalURL(path)
        guard let image = UIImage(contentsOfFile: localURL.path) else {
            return nil
        }
        let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        cachedArtworkPath = path
        cachedArtwork = artwork
        return artwork
    }

    private func isPlayingOnMain() -> Bool {
        guard let player else { return false }
        return player.timeControlStatus == .playing || player.rate > 0
    }

    private func currentPositionSecondsOnMain() -> Double {
        guard let player else { return 0 }
        let seconds = CMTimeGetSeconds(player.currentTime())
        return seconds.isFinite && seconds > 0 ? seconds : 0
    }

    private func currentDurationSecondsOnMain() -> Double? {
        guard let duration = player?.currentItem?.duration else { return nil }
        let seconds = CMTimeGetSeconds(duration)
        return seconds.isFinite && seconds > 0 ? seconds : nil
    }

    private func resolveLocalURL(_ rawPath: String) -> URL {
        if rawPath.hasPrefix("file://"), let url = URL(string: rawPath) {
            return url
        }
        return URL(fileURLWithPath: rawPath)
    }

    private func runOnMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    private func runOnMainSync<T>(_ block: () -> T) -> T {
        if Thread.isMainThread {
            return block()
        }
        return DispatchQueue.main.sync(execute: block)
    }
}
