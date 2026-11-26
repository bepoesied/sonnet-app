import Foundation
import UIKit

/// Local-first cover image loader with stable `bookId`-keyed memory and disk caching.
///
/// Matches Android's Coil strategy and `docs/MOBILE.md`: presigned remote cover URLs expire,
/// so images are cached by stable `bookId` rather than by raw presigned URL.
final class CoverImageLoader {
    static let shared = CoverImageLoader()

    private let memoryCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 64 * 1024 * 1024
        return cache
    }()

    private let diskCacheDirectory: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = caches.appendingPathComponent("image_cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()

    private init() {}

    func cachedImage(bookId: String?, localCoverPath: String?) -> UIImage? {
        let cacheKey = cacheKeyFor(bookId: bookId, localCoverPath: localCoverPath, remoteCoverUrl: nil)
        if let cacheKey, let cached = memoryCache.object(forKey: cacheKey as NSString) {
            return cached
        }
        return nil
    }

    func loadImage(
        bookId: String?,
        localCoverPath: String?,
        remoteCoverUrl: String?
    ) async -> UIImage? {
        let normalizedLocal = localCoverPath?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedRemote = remoteCoverUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cacheKey = cacheKeyFor(
            bookId: bookId,
            localCoverPath: normalizedLocal,
            remoteCoverUrl: normalizedRemote
        )

        if let cacheKey, let memCached = memoryCache.object(forKey: cacheKey as NSString) {
            return memCached
        }

        // 1. Prefer local downloaded cover file when present.
        if let localPath = normalizedLocal, !localPath.isEmpty {
            let fileURL = resolveLocalURL(localPath)
            if let image = await decodeImageFromDisk(url: fileURL) {
                storeInMemory(image, key: cacheKey)
                return image
            }
        }

        // 2. Check stable-keyed disk cache before hitting the network.
        if let diskURL = diskCacheURL(for: cacheKey),
           FileManager.default.fileExists(atPath: diskURL.path),
           let diskImage = await decodeImageFromDisk(url: diskURL) {
            storeInMemory(diskImage, key: cacheKey)
            return diskImage
        }

        // 3. Fetch remote presigned cover URL and cache under the stable book key.
        guard let remoteString = normalizedRemote,
              !remoteString.isEmpty,
              let url = URL(string: remoteString) else {
            return nil
        }

        do {
            let (data, response) = try await session.data(from: url)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200..<300).contains(httpResponse.statusCode),
                  let image = UIImage(data: data) else {
                return nil
            }
            storeInMemory(image, key: cacheKey)
            if let diskURL = diskCacheURL(for: cacheKey) {
                try? data.write(to: diskURL, options: .atomic)
            }
            return image
        } catch {
            return nil
        }
    }

    private func decodeImageFromDisk(url: URL) async -> UIImage? {
        await Task.detached(priority: .userInitiated) {
            UIImage(contentsOfFile: url.path)
        }.value
    }

    private func storeInMemory(_ image: UIImage, key: String?) {
        guard let key else { return }
        let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
        memoryCache.setObject(image, forKey: key as NSString, cost: max(cost, 1))
    }

    private func cacheKeyFor(
        bookId: String?,
        localCoverPath: String?,
        remoteCoverUrl: String?
    ) -> String? {
        if let bookId, !bookId.isEmpty {
            return "book-cover-\(bookId)"
        }
        if let localCoverPath, !localCoverPath.isEmpty {
            return localCoverPath
        }
        if let remoteCoverUrl, !remoteCoverUrl.isEmpty {
            return remoteCoverUrl
        }
        return nil
    }

    private func diskCacheURL(for cacheKey: String?) -> URL? {
        guard let cacheKey, cacheKey.hasPrefix("book-cover-") else { return nil }
        let sanitized = cacheKey.replacingOccurrences(
            of: "[^a-zA-Z0-9_-]",
            with: "_",
            options: .regularExpression
        )
        return diskCacheDirectory.appendingPathComponent("\(sanitized).img")
    }

    private func resolveLocalURL(_ rawPath: String) -> URL {
        if rawPath.hasPrefix("file://"), let url = URL(string: rawPath) {
            return url
        }
        return URL(fileURLWithPath: rawPath)
    }
}
