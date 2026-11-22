import Foundation
import Observation
import SwiftUI
import sharedKit

/// Root `@Observable` store owning `IosAppContainer`, platform adapters, and root auth/playback state.
@MainActor
@Observable
final class AppStore {
    static let shared = AppStore()

    let container: IosAppContainer
    let authPresenter: WebAuthSessionPresenter

    private let networkMonitorBridge: NWPathNetworkMonitor
    private let audioDriver: AVFoundationPlaybackDriver
    private let downloadScheduler: IosBookDownloadScheduler
    private let backgroundSyncScheduler: IosBackgroundSyncScheduler
    private let appViewModelHandle: AppViewModelHandle

    private var appStateSubscription: FlowSubscription?
    private var playerStateSubscription: FlowSubscription?
    private var pendingPlayerRequestSubscription: FlowSubscription?

    private(set) var appState: AppUiState
    private(set) var activePlayerState: PlayerUiState
    private(set) var pendingPlayerRequest: String?

    private var wasAuthenticated: Bool = false

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
        self.activePlayerState = PlayerUiState(
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

        appStateSubscription = appHandle.observeState { [weak self] newState in
            self?.handleAppStateUpdate(newState)
        }

        playerStateSubscription = container.observePlayerState { [weak self] newState in
            self?.activePlayerState = newState
        }

        pendingPlayerRequestSubscription = container.observePendingPlayerRequest { [weak self] request in
            self?.pendingPlayerRequest = request
        }
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

    var hasActiveBook: Bool {
        activePlayerState.bookId != nil
    }

    func consumePendingPlayerRequest() -> String? {
        let request = container.playbackOrchestrator.consumePendingPlayerRequest()
        pendingPlayerRequest = nil
        return request
    }

    func toggleActivePlayback() {
        container.playbackOrchestrator.playPause()
    }

    func logout() {
        downloadScheduler.cancelAll()
        container.stopPlaybackIfInitialized()
        appViewModelHandle.logout()
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            container.onAppDidEnterBackground()
        case .active:
            if isAuthenticated && !requiresReauth {
                Task { [weak container] in
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

        if !authenticated && wasAuthenticated {
            downloadScheduler.cancelAll()
            container.stopPlaybackIfInitialized()
        }
        wasAuthenticated = authenticated
    }

    deinit {
        appStateSubscription?.cancel()
        playerStateSubscription?.cancel()
        pendingPlayerRequestSubscription?.cancel()
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
    private let handle: LoginViewModelHandle
    private let authPresenter: WebAuthSessionPresenter
    private let onLoginCompleted: () -> Void

    private var stateSubscription: FlowSubscription?
    private var effectsSubscription: FlowSubscription?

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

        stateSubscription = handle.observeState { [weak self] newState in
            self?.uiState = newState
        }

        effectsSubscription = handle.observeEffects { [weak self] effect in
            self?.handleEffect(effect)
        }
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
        stateSubscription?.cancel()
        effectsSubscription?.cancel()
        handle.clear()
    }
}

/// `@Observable` wrapper around shared `LibraryViewModelHandle`.
@MainActor
@Observable
final class LibraryStore {
    private let handle: LibraryViewModelHandle
    private var stateSubscription: FlowSubscription?

    private(set) var uiState: LibraryUiState

    init(container: IosAppContainer) {
        let handle = container.createLibraryViewModelHandle()
        self.handle = handle
        self.uiState = handle.currentState

        stateSubscription = handle.observeState { [weak self] newState in
            self?.uiState = newState
        }
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
        stateSubscription?.cancel()
        handle.clear()
    }
}

/// `@Observable` wrapper around shared `PlayerViewModelHandle`.
@MainActor
@Observable
final class PlayerStore {
    let bookId: String
    private let handle: PlayerViewModelHandle
    private var stateSubscription: FlowSubscription?

    private(set) var uiState: PlayerUiState

    init(container: IosAppContainer, bookId: String) {
        self.bookId = bookId
        let handle = container.createPlayerViewModelHandle(bookId: bookId)
        self.handle = handle
        self.uiState = handle.currentState

        stateSubscription = handle.observeState { [weak self] newState in
            self?.uiState = newState
        }
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
        stateSubscription?.cancel()
        handle.clear()
    }
}
