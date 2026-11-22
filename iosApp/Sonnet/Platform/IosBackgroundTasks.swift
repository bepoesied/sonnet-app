import BackgroundTasks
import Foundation
import UIKit
import sharedKit

enum SonnetBackgroundTasks {
    static let progressSyncIdentifier = "pw.kmr.sonnet.progress-sync"
    static let downloadIdentifier = "pw.kmr.sonnet.download"

    static func register(containerProvider: @escaping () -> IosAppContainer?) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: progressSyncIdentifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleProgressSync(task: refreshTask, container: containerProvider())
        }

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: downloadIdentifier,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleDownloadResume(task: processingTask, container: containerProvider())
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

    private static func handleProgressSync(task: BGAppRefreshTask, container: IosAppContainer?) {
        guard let container else {
            task.setTaskCompleted(success: false)
            return
        }

        let work = Task {
            do {
                let completed = try await container.syncPendingProgressForBackgroundTask().boolValue
                if !completed {
                    scheduleProgressSync()
                }
                task.setTaskCompleted(success: completed)
            } catch {
                scheduleProgressSync()
                task.setTaskCompleted(success: false)
            }
        }

        task.expirationHandler = {
            work.cancel()
            scheduleProgressSync()
            task.setTaskCompleted(success: false)
        }
    }

    private static func handleDownloadResume(task: BGProcessingTask, container: IosAppContainer?) {
        guard let container else {
            task.setTaskCompleted(success: false)
            return
        }

        let work = Task {
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
                task.setTaskCompleted(success: allSucceeded)
            } catch {
                scheduleDownloadResume()
                task.setTaskCompleted(success: false)
            }
        }

        task.expirationHandler = {
            work.cancel()
            scheduleDownloadResume()
            task.setTaskCompleted(success: false)
        }
    }
}

/// iOS implementation of `BackgroundSyncScheduler` backed by `BGAppRefreshTask`.
final class IosBackgroundSyncScheduler: NSObject, BackgroundSyncScheduler {
    func scheduleProgressSync() {
        SonnetBackgroundTasks.scheduleProgressSync()
    }
}

/// iOS implementation of `BookDownloadScheduler` that runs per-book downloads with
/// `UIApplication.beginBackgroundTask` protection and `BGProcessingTask` resumption.
final class IosBookDownloadScheduler: NSObject, BookDownloadScheduler {
    private let lock = NSLock()
    private weak var container: IosAppContainer?
    private var activeDownloads: [String: ActiveDownload] = [:]

    private struct ActiveDownload {
        let task: Task<Void, Never>
        var backgroundTaskId: UIBackgroundTaskIdentifier
    }

    func attach(container: IosAppContainer) {
        lock.lock()
        self.container = container
        lock.unlock()
    }

    func enqueueDownload(bookId: String, restartInProgress: Bool) {
        lock.lock()
        guard activeDownloads[bookId] == nil, let container else {
            lock.unlock()
            return
        }
        lock.unlock()

        var bgTaskId: UIBackgroundTaskIdentifier = .invalid
        let beginBgTask = {
            bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "sonnet-download-\(bookId)") { [weak self] in
                SonnetBackgroundTasks.scheduleDownloadResume()
                self?.finishBackgroundTask(bookId: bookId)
            }
        }
        if Thread.isMainThread {
            beginBgTask()
        } else {
            DispatchQueue.main.sync(execute: beginBgTask)
        }

        let downloadTask = Task.detached(priority: .utility) { [weak self] in
            defer {
                self?.cleanupDownload(bookId: bookId)
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
        activeDownloads[bookId] = ActiveDownload(task: downloadTask, backgroundTaskId: bgTaskId)
        lock.unlock()
    }

    func cancelDownload(bookId: String) {
        lock.lock()
        let active = activeDownloads.removeValue(forKey: bookId)
        lock.unlock()

        active?.task.cancel()
        if let bgTaskId = active?.backgroundTaskId, bgTaskId != .invalid {
            endBackgroundTask(bgTaskId)
        }
    }

    func cancelAll() {
        lock.lock()
        let downloads = activeDownloads
        activeDownloads.removeAll()
        lock.unlock()

        for (_, active) in downloads {
            active.task.cancel()
            if active.backgroundTaskId != .invalid {
                endBackgroundTask(active.backgroundTaskId)
            }
        }
    }

    private func cleanupDownload(bookId: String) {
        lock.lock()
        let active = activeDownloads.removeValue(forKey: bookId)
        lock.unlock()

        if let bgTaskId = active?.backgroundTaskId, bgTaskId != .invalid {
            endBackgroundTask(bgTaskId)
        }
    }

    private func finishBackgroundTask(bookId: String) {
        lock.lock()
        let active = activeDownloads[bookId]
        if let active {
            activeDownloads[bookId] = ActiveDownload(task: active.task, backgroundTaskId: .invalid)
        }
        lock.unlock()

        if let bgTaskId = active?.backgroundTaskId, bgTaskId != .invalid {
            endBackgroundTask(bgTaskId)
        }
    }

    private func endBackgroundTask(_ id: UIBackgroundTaskIdentifier) {
        DispatchQueue.main.async {
            UIApplication.shared.endBackgroundTask(id)
        }
    }
}
