import Foundation
import Observation
import SwiftUI
import sharedKit

/// Non-isolated owner for Kotlin `FlowSubscription`s so `@MainActor @Observable` stores can
/// safely cancel observations from `deinit` without touching actor-isolated getters.
final class SubscriptionBag {
    private let lock = NSLock()
    private var subscriptions: [FlowSubscription] = []

    func insert(_ subscription: FlowSubscription) {
        lock.lock()
        subscriptions.append(subscription)
        lock.unlock()
    }

    func cancelAll() {
        lock.lock()
        let items = subscriptions
        subscriptions.removeAll()
        lock.unlock()

        items.forEach { $0.cancel() }
    }

    deinit {
        cancelAll()
    }
}

/// Separate `@Observable` store for 1 Hz active playback state so `MiniPlayerBar` ticks never
/// invalidate `RootView` or `LibraryView`.
@MainActor
@Observable
final class ActivePlaybackStore {
    private(set) var state: PlayerUiState = Self.emptyState()
    private(set) var hasActiveBook: Bool = false

    func update(_ newState: PlayerUiState) {
        state = newState
        let active = newState.bookId != nil
        if hasActiveBook != active {
            hasActiveBook = active
        }
    }

    func reset() {
        state = Self.emptyState()
        hasActiveBook = false
    }

    static func emptyState() -> PlayerUiState {
        PlayerUiState(
            bookId: nil,
            title: "",
            author: nil,
            coverFilePath: nil,
            chapters: [],
            currentChapterId: nil,
            currentChapterTitle: "",
            currentChapterStartPositionMs: 0,
            currentChapterPositionMs: 0,
            currentChapterDurationMs: 0,
            isPlaying: false,
            positionMs: 0,
            durationMs: 0,
            canPlay: false,
            bookMissing: false,
            isCheckingRemoteState: false,
            sleepTimer: SleepTimerStateOff.shared,
            resumePrompt: nil,
            errorMessage: nil
        )
    }
}

/// Root `@Observable` store owning `IosAppContainer`, platform adapters, and root auth/playback state.
@MainActor
@Observable
final class AppStore {
    static let shared = AppStore()

    @ObservationIgnored let container: IosAppContainer
    @ObservationIgnored let authPresenter: WebAuthSessionPresenter
    @ObservationIgnored let activePlayback = ActivePlaybackStore()

    @ObservationIgnored private let networkMonitorBridge: NWPathNetworkMonitor
    @ObservationIgnored private let audioDriver: AVFoundationPlaybackDriver
    @ObservationIgnored private let downloadScheduler: IosBookDownloadScheduler
    @ObservationIgnored private let backgroundSyncScheduler: IosBackgroundSyncScheduler
    @ObservationIgnored private let appViewModelHandle: AppViewModelHandle
    @ObservationIgnored private let rootSubscriptions = SubscriptionBag()
    @ObservationIgnored private let playbackSubscriptions = SubscriptionBag()
    @ObservationIgnored private var observingPlayback: Bool = false
    @ObservationIgnored private var wasAuthenticated: Bool = false

    private(set) var appState: AppUiState
    private(set) var pendingPlayerRequest: String?

    init() {
        let secureStore = KeychainSecureStore()
        let preferencesStore = UserDefaultsKeyValueStore()
        let oidcCrypto = CryptoKitOidcCrypto()
        let audioDriver = AVFoundationPlaybackDriver()
        let downloadScheduler = IosBookDownloadScheduler()
        let backgroundSyncScheduler = IosBackgroundSyncScheduler()
        let networkBridge = NWPathNetworkMonitor()

        #if DEBUG
        let enforcesHttps = false
        #else
        let enforcesHttps = true
        #endif

        let dependencies = IosPlatformDependencies(
            secureStore: secureStore,
            preferencesStore: preferencesStore,
            oidcCrypto: oidcCrypto,
            audioDriver: audioDriver,
            bookDownloadScheduler: downloadScheduler,
            backgroundSyncScheduler: backgroundSyncScheduler,
            enforcesHttps: enforcesHttps,
            initialOnline: networkBridge.isCurrentlyOnline,
            onStartNetworkMonitoring: { [weak networkBridge] in
                networkBridge?.start()
            },
            onStopNetworkMonitoring: { [weak networkBridge] in
                networkBridge?.stop()
            }
        )

        let container = IosAppContainer(deps: dependencies)
        downloadScheduler.attach(container: container)
        networkBridge.attach(target: container.networkMonitor)
        networkBridge.start()

        let appHandle = container.createAppViewModelHandle()

        self.container = container
        self.authPresenter = WebAuthSessionPresenter()
        self.networkMonitorBridge = networkBridge
        self.audioDriver = audioDriver
        self.downloadScheduler = downloadScheduler
        self.backgroundSyncScheduler = backgroundSyncScheduler
        self.appViewModelHandle = appHandle
        self.appState = appHandle.currentState

        rootSubscriptions.insert(
            appHandle.observeState { [weak self] newState in
                self?.handleAppStateUpdate(newState)
            }
        )
    }

    var isLoading: Bool {
        appState is AppUiStateLoading
    }

    var readyState: AppUiStateReady? {
        appState as? AppUiStateReady
    }

    var isAuthenticated: Bool {
        readyState?.session != nil
    }

    var requiresReauth: Bool {
        readyState?.requiresReauth ?? false
    }

    func consumePendingPlayerRequest() -> String? {
        guard observingPlayback else { return nil }
        let request = container.playbackOrchestrator.consumePendingPlayerRequest()
        pendingPlayerRequest = nil
        return request
    }

    func toggleActivePlayback() {
        container.playbackOrchestrator.playPause()
    }

    func logout() {
        downloadScheduler.cancelAll()
        stopObservingPlayback()
        container.stopPlaybackIfInitialized()
        appViewModelHandle.logout()
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            container.onAppDidEnterBackground()
        case .active:
            if isAuthenticated && !requiresReauth {
                Task { [weak container = self.container] in
                    _ = try? await container?.syncPendingProgressForBackgroundTask()
                }
            }
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    func handleWillTerminate() {
        container.flushPlaybackProgressIfInitialized(forceSync: true)
    }

    private func handleAppStateUpdate(_ newState: AppUiState) {
        appState = newState

        let ready = newState as? AppUiStateReady
        let authenticated = ready?.session != nil
        let canSync = authenticated && !(ready?.requiresReauth ?? false)

        if canSync {
            container.startSyncCoordinator()
        } else {
            container.stopSyncCoordinatorIfInitialized()
        }

        if authenticated {
            startObservingPlaybackIfNeeded()
        } else {
            stopObservingPlayback()
            if wasAuthenticated {
                downloadScheduler.cancelAll()
                container.stopPlaybackIfInitialized()
            }
        }
        wasAuthenticated = authenticated
    }

    private func startObservingPlaybackIfNeeded() {
        guard !observingPlayback else { return }
        observingPlayback = true

        playbackSubscriptions.insert(
            container.observePlayerState { [weak self] newState in
                self?.activePlayback.update(newState)
            }
        )
        playbackSubscriptions.insert(
            container.observePendingPlayerRequest { [weak self] request in
                self?.pendingPlayerRequest = request
            }
        )
    }

    private func stopObservingPlayback() {
        guard observingPlayback else { return }
        observingPlayback = false
        playbackSubscriptions.cancelAll()
        pendingPlayerRequest = nil
        activePlayback.reset()
    }

    deinit {
        rootSubscriptions.cancelAll()
        playbackSubscriptions.cancelAll()
        appViewModelHandle.clear()
        downloadScheduler.cancelAll()
        networkMonitorBridge.stop()
        container.dispose()
    }
}

/// `@Observable` wrapper around shared `LoginViewModelHandle`.
@MainActor
@Observable
final class LoginStore {
    @ObservationIgnored private let handle: LoginViewModelHandle
    @ObservationIgnored private let authPresenter: WebAuthSessionPresenter
    @ObservationIgnored private let onLoginCompleted: () -> Void
    @ObservationIgnored private let subscriptions = SubscriptionBag()

    private(set) var uiState: LoginUiState

    init(
        container: IosAppContainer,
        authPresenter: WebAuthSessionPresenter,
        onLoginCompleted: @escaping () -> Void
    ) {
        let handle = container.createLoginViewModelHandle()
        self.handle = handle
        self.authPresenter = authPresenter
        self.onLoginCompleted = onLoginCompleted
        self.uiState = handle.currentState

        subscriptions.insert(
            handle.observeState { [weak self] newState in
                self?.uiState = newState
            }
        )

        subscriptions.insert(
            handle.observeEffects { [weak self] effect in
                self?.handleEffect(effect)
            }
        )
    }

    var canSubmit: Bool {
        !uiState.isLoading && !uiState.serverUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func updateServerUrl(_ url: String) {
        handle.onServerUrlChange(serverUrl: url)
    }

    func startLogin() {
        handle.startLogin()
    }

    private func handleEffect(_ effect: LoginEffect) {
        if let openBrowser = effect as? LoginEffectOpenAuthBrowser,
           let request = openBrowser.authData as? OidcAuthorizationRequest {
            Task { @MainActor [weak self] in
                guard let self else { return }
                let result = await self.authPresenter.authenticate(request: request)
                self.handle.completeLogin(result: result)
            }
        } else if effect is LoginEffectLoginCompleted {
            onLoginCompleted()
        }
    }

    deinit {
        subscriptions.cancelAll()
        handle.clear()
    }
}

/// `@Observable` wrapper around shared `LibraryViewModelHandle`.
@MainActor
@Observable
final class LibraryStore {
    @ObservationIgnored private let handle: LibraryViewModelHandle
    @ObservationIgnored private let subscriptions = SubscriptionBag()

    private(set) var uiState: LibraryUiState

    init(container: IosAppContainer) {
        let handle = container.createLibraryViewModelHandle()
        self.handle = handle
        self.uiState = handle.currentState

        subscriptions.insert(
            handle.observeState { [weak self] newState in
                self?.uiState = newState
            }
        )
    }

    func refresh(showProgress: Bool = true) {
        handle.refresh(showProgress: showProgress)
    }

    func refreshAndWait() async {
        handle.refresh(showProgress: true)
        try? await Task.sleep(for: .milliseconds(50))
        while uiState.isRefreshing {
            try? await Task.sleep(for: .milliseconds(100))
            if Task.isCancelled { break }
        }
    }

    func clearError() {
        handle.clearError()
    }

    func updateSearchQuery(_ query: String) {
        handle.updateSearchQuery(query: query)
    }

    func toggleCompletion(for book: LibraryBook) {
        handle.swipeCompletionAction(book: book)
    }

    func performDownloadAction(for book: LibraryBook) {
        handle.swipeDownloadAction(book: book)
    }

    deinit {
        subscriptions.cancelAll()
        handle.clear()
    }
}

/// `@Observable` wrapper around shared `PlayerViewModelHandle`.
@MainActor
@Observable
final class PlayerStore {
    @ObservationIgnored let bookId: String
    @ObservationIgnored private let handle: PlayerViewModelHandle
    @ObservationIgnored private let subscriptions = SubscriptionBag()

    private(set) var uiState: PlayerUiState

    init(container: IosAppContainer, bookId: String) {
        self.bookId = bookId
        let handle = container.createPlayerViewModelHandle(bookId: bookId)
        self.handle = handle
        self.uiState = handle.currentState

        subscriptions.insert(
            handle.observeState { [weak self] newState in
                self?.uiState = newState
            }
        )
    }

    var isUnavailable: Bool {
        uiState.bookMissing ||
            (uiState.errorMessage != nil && !uiState.canPlay && uiState.chapters.isEmpty)
    }

    func loadBook() {
        handle.loadBook()
    }

    func playPause() {
        handle.playPause()
    }

    func seekBack() {
        handle.seekBack()
    }

    func seekForward() {
        handle.seekForward()
    }

    func seekTo(positionMs: Int64) {
        handle.seekTo(positionMs: positionMs)
    }

    func jumpToChapter(chapterId: String) {
        handle.jumpToChapter(chapterId: chapterId)
    }

    func setSleepTimer(_ timer: SleepTimerState) {
        handle.setSleepTimer(timer: timer)
    }

    func useRemoteProgress() {
        handle.useRemoteProgress()
    }

    func keepLocalProgress() {
        handle.keepLocalProgress()
    }

    func clearError() {
        handle.clearError()
    }

    deinit {
        subscriptions.cancelAll()
        handle.clear()
    }
}
