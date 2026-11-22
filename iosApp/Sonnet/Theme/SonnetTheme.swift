import SwiftUI
import UIKit
import sharedKit

enum SonnetColors {
    /// Warm bookish Amber accent matching Android's SonnetAmberLight / SonnetAmberDark.
    static let accent = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0xF9 / 255.0, green: 0xBA / 255.0, blue: 0x71 / 255.0, alpha: 1.0)
            : UIColor(red: 0x83 / 255.0, green: 0x54 / 255.0, blue: 0x15 / 255.0, alpha: 1.0)
    })

    static let accentContainer = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0x66 / 255.0, green: 0x3E / 255.0, blue: 0x00 / 255.0, alpha: 1.0)
            : UIColor(red: 0xFF / 255.0, green: 0xDD / 255.0, blue: 0xAF / 255.0, alpha: 1.0)
    })

    static let onAccentContainer = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0xFF / 255.0, green: 0xDD / 255.0, blue: 0xAF / 255.0, alpha: 1.0)
            : UIColor(red: 0x2B / 255.0, green: 0x17 / 255.0, blue: 0x00 / 255.0, alpha: 1.0)
    })

    static let secondaryContainer = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0x57 / 255.0, green: 0x43 / 255.0, blue: 0x2B / 255.0, alpha: 1.0)
            : UIColor(red: 0xFC / 255.0, green: 0xDD / 255.0, blue: 0xBD / 255.0, alpha: 1.0)
    })

    static let onSecondaryContainer = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0xFC / 255.0, green: 0xDD / 255.0, blue: 0xBD / 255.0, alpha: 1.0)
            : UIColor(red: 0x27 / 255.0, green: 0x19 / 255.0, blue: 0x04 / 255.0, alpha: 1.0)
    })

    static let sage = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0xB9 / 255.0, green: 0xCE / 255.0, blue: 0xA1 / 255.0, alpha: 1.0)
            : UIColor(red: 0x52 / 255.0, green: 0x64 / 255.0, blue: 0x3F / 255.0, alpha: 1.0)
    })
}

enum DurationFormatting {
    static func formatDuration(ms: Int64) -> String {
        let totalSeconds = max(ms / 1000, 0)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
        }
        return String(format: "%lld:%02lld", minutes, seconds)
    }

    static func formatSleepTimerCountdown(remainingMs: Int64) -> String {
        let totalSeconds = Int64(ceil(Double(max(remainingMs, 0)) / 1000.0))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
        }
        return String(format: "%lld:%02lld", minutes, seconds)
    }
}

extension SleepTimerState {
    var isActive: Bool {
        !(self is SleepTimerStateOff)
    }

    var displayLabel: String {
        if self is SleepTimerStateOff {
            return "Off"
        }
        if self is SleepTimerStateChapterEnd {
            return "End of chapter"
        }
        if let countdown = self as? SleepTimerStateCountdown {
            return DurationFormatting.formatSleepTimerCountdown(remainingMs: countdown.remainingMs)
        }
        return "Off"
    }
}

extension DownloadStatus {
    var statusLabel: String {
        switch self {
        case .notDownloaded:
            return "Not downloaded"
        case .queued:
            return "Queued"
        case .downloading:
            return "Downloading"
        case .downloaded:
            return "Downloaded"
        case .failed:
            return "Failed"
        default:
            return "Not downloaded"
        }
    }

    var actionLabel: String {
        switch self {
        case .notDownloaded:
            return "Download"
        case .failed:
            return "Retry"
        case .queued, .downloading:
            return "Cancel"
        case .downloaded:
            return "Delete"
        default:
            return "Download"
        }
    }

    var statusColor: Color {
        switch self {
        case .notDownloaded:
            return .secondary
        case .queued:
            return SonnetColors.sage
        case .downloading, .downloaded:
            return SonnetColors.accent
        case .failed:
            return .red
        default:
            return .secondary
        }
    }
}

extension LibraryBook {
    var downloadProgressValue: Double? {
        guard let total = totalChapters?.int64Value, total > 0 else {
            return nil
        }
        return min(max(Double(downloadedChapters) / Double(total), 0.0), 1.0)
    }
}
