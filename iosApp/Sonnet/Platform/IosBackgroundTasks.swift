import BackgroundTasks
import Foundation
import UIKit
import sharedKit

enum SonnetBackgroundTasks {
    static let progressSyncIdentifier = "pw.kmr.sonnet.progress-sync"
    static let downloadIdentifier = "pw.kmr.sonnet.download"

    static func register(containerProvider: @escaping @MainActor () -> IosAppContainer) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: progressSyncIdentifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleProgressSync(task: refreshTask, containerProvider: containerProvider)
        }

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: downloadIdentifier,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleDownloadResume(task: processingTask, containerProvider: containerProvider)
        }
    }

    static func scheduleProgressSync() {
        let request = BGAppRefreshTaskRequest(identifier: progressSyncIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func scheduleDownloadResume() {
        let request = BGProcessingTaskRequest(identifier: downloadIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handleProgressSync(
        task: BGAppRefreshTask,
        containerProvider: @escaping @MainActor () -> IosAppContainer
    ) {
        let completion = OneShotBGTaskCompletion(task: task)

        let work = Task {
            let container = await MainActor.run { containerProvider() }
            do {
                let completed = try await container.syncPendingProgressForBackgroundTask().boolValue
                if !completed {
                    scheduleProgressSync()
                }
                completion.complete(success: completed)
            } catch {
                scheduleProgressSync()
                completion.complete(success: false)
            }
        }

        task.expirationHandler = {
            work.cancel()
            scheduleProgressSync()
            completion.complete(success: false)
        }
    }

    private static func handleDownloadResume(
        task: BGProcessingTask,
        containerProvider: @escaping @MainActor () -> IosAppContainer
    ) {
        let completion = OneShotBGTaskCompletion(task: task)

        let work = Task {
            let container = await MainActor.run { containerProvider() }
            do {
                let bookIds = try await container.interruptedDownloadBookIds()
                var allSucceeded = true
                for bookId in bookIds {
                    try Task.checkCancellation()
                    do {
                        try await container.downloadBookForBackgroundTask(
                            bookId: bookId,
                            restartInProgress: true
                        )
                    } catch {
                        allSucceeded = false
                    }
                }
                if !allSucceeded {
                    scheduleDownloadResume()
                }
                completion.complete(success: allSucceeded)
            } catch {
                scheduleDownloadResume()
                completion.complete(success: false)
            }
        }

        task.expirationHandler = {
            work.cancel()
            Task { @MainActor in
                containerProvider().cancelAllBookDownloads()
            }
            scheduleDownloadResume()
            completion.complete(success: false)
        }
    }
}

private final class OneShotBGTaskCompletion {
    private let lock = NSLock()
    private var completed = false
    private let task: BGTask

    init(task: BGTask) {
        self.task = task
    }

    func complete(success: Bool) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return
        }
        completed = true
        lock.unlock()

        task.setTaskCompleted(success: success)
    }
}

/// iOS implementation of `BackgroundSyncScheduler` backed by `BGAppRefreshTask`.
final class IosBackgroundSyncScheduler: NSObject, BackgroundSyncScheduler {
    func scheduleProgressSync() {
        SonnetBackgroundTasks.scheduleProgressSync()
    }
}

/// iOS implementation of `BookDownloadScheduler` that runs per-book downloads with
/// `UIApplication.beginBackgroundTask` protection, true Kotlin coroutine cancellation,
/// and `BGProcessingTask` resumption.
final class IosBookDownloadScheduler: NSObject, BookDownloadScheduler {
    private let lock = NSLock()
    private weak var container: IosAppContainer?
    private var activeDownloads: [String: ActiveDownload] = [:]

    private struct ActiveDownload {
        let token: UUID
        var task: Task<Void, Never>?
        var backgroundTaskId: UIBackgroundTaskIdentifier
    }

    func attach(container: IosAppContainer) {
        lock.lock()
        self.container = container
        lock.unlock()
    }

    func enqueueDownload(bookId: String, restartInProgress: Bool) {
        let token = UUID()

        lock.lock()
        guard activeDownloads[bookId] == nil, let container else {
            lock.unlock()
            return
        }
        // Reserve the slot before starting async work to prevent concurrent registration races.
        activeDownloads[bookId] = ActiveDownload(token: token, task: nil, backgroundTaskId: .invalid)
        lock.unlock()

        var bgTaskId: UIBackgroundTaskIdentifier = .invalid
        let beginBgTask = {
            bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "sonnet-download-\(bookId)") { [weak self] in
                SonnetBackgroundTasks.scheduleDownloadResume()
                self?.finishBackgroundTask(bookId: bookId, token: token)
            }
        }
        if Thread.isMainThread {
            beginBgTask()
        } else {
            DispatchQueue.main.sync(execute: beginBgTask)
        }

        let downloadTask = Task.detached(priority: .utility) { [weak self] in
            defer {
                self?.cleanupDownload(bookId: bookId, token: token)
            }
            do {
                try await container.downloadBookForBackgroundTask(
                    bookId: bookId,
                    restartInProgress: restartInProgress
                )
            } catch is CancellationError {
                // Cancellation is handled inside LibraryRepository.
            } catch {
                if !Task.isCancelled {
                    SonnetBackgroundTasks.scheduleDownloadResume()
                }
            }
        }

        lock.lock()
        if var entry = activeDownloads[bookId], entry.token == token {
            entry.task = downloadTask
            entry.backgroundTaskId = bgTaskId
            activeDownloads[bookId] = entry
            lock.unlock()
        } else {
            // Cancelled before the task was stored.
            lock.unlock()
            downloadTask.cancel()
            container.cancelBookDownload(bookId: bookId)
            if bgTaskId != .invalid {
                endBackgroundTask(bgTaskId)
            }
        }
    }

    func cancelDownload(bookId: String) {
        lock.lock()
        let active = activeDownloads.removeValue(forKey: bookId)
        let container = self.container
        lock.unlock()

        container?.cancelBookDownload(bookId: bookId)
        active?.task?.cancel()
        if let bgTaskId = active?.backgroundTaskId, bgTaskId != .invalid {
            endBackgroundTask(bgTaskId)
        }
    }

    func cancelAll() {
        lock.lock()
        let downloads = activeDownloads
        activeDownloads.removeAll()
        let container = self.container
        lock.unlock()

        container?.cancelAllBookDownloads()
        for (_, active) in downloads {
            active.task?.cancel()
            if active.backgroundTaskId != .invalid {
                endBackgroundTask(active.backgroundTaskId)
            }
        }
    }

    private func cleanupDownload(bookId: String, token: UUID) {
        lock.lock()
        guard let existing = activeDownloads[bookId], existing.token == token else {
            lock.unlock()
            return
        }
        activeDownloads.removeValue(forKey: bookId)
        lock.unlock()

        if existing.backgroundTaskId != .invalid {
            endBackgroundTask(existing.backgroundTaskId)
        }
    }

    private func finishBackgroundTask(bookId: String, token: UUID) {
        lock.lock()
        guard var existing = activeDownloads[bookId], existing.token == token else {
            lock.unlock()
            return
        }
        let bgTaskId = existing.backgroundTaskId
        existing.backgroundTaskId = .invalid
        activeDownloads[bookId] = existing
        lock.unlock()

        if bgTaskId != .invalid {
            endBackgroundTask(bgTaskId)
        }
    }

    private func endBackgroundTask(_ id: UIBackgroundTaskIdentifier) {
        DispatchQueue.main.async {
            UIApplication.shared.endBackgroundTask(id)
        }
    }
}
