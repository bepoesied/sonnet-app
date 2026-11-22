import Foundation
import Network
import sharedKit

/// Bridges iOS `NWPathMonitor` reachability updates into shared `ReactiveNetworkMonitor`.
final class NWPathNetworkMonitor {
    private let queue = DispatchQueue(label: "pw.kmr.sonnet.network-monitor", qos: .utility)
    private let lock = NSLock()
    private var monitor: NWPathMonitor?
    private var online: Bool = true
    private weak var target: ReactiveNetworkMonitor?

    var isCurrentlyOnline: Bool {
        lock.lock()
        defer { lock.unlock() }
        return online
    }

    func attach(target: ReactiveNetworkMonitor) {
        lock.lock()
        self.target = target
        let current = online
        lock.unlock()

        DispatchQueue.main.async {
            target.updateOnlineStatus(online: current)
        }
    }

    func start() {
        lock.lock()
        if monitor != nil {
            lock.unlock()
            return
        }
        let newMonitor = NWPathMonitor()
        monitor = newMonitor
        lock.unlock()

        newMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let isSatisfied = path.status == .satisfied
            self.lock.lock()
            self.online = isSatisfied
            let target = self.target
            self.lock.unlock()

            DispatchQueue.main.async {
                target?.updateOnlineStatus(online: isSatisfied)
            }
        }
        newMonitor.start(queue: queue)
    }

    func stop() {
        lock.lock()
        let existing = monitor
        monitor = nil
        lock.unlock()

        existing?.pathUpdateHandler = nil
        existing?.cancel()
    }

    deinit {
        stop()
    }
}
