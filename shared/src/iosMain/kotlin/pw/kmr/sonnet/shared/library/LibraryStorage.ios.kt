package pw.kmr.sonnet.shared.library

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Returns a persistent parent directory under Application Support marked with
 * `NSURLIsExcludedFromBackupKey = true`. Because [LibraryRepository.clearLocalLibrary] deletes
 * the child `books/` folder on logout, excluding this stable parent ensures recreated download
 * directories continue to inherit iCloud backup exclusion automatically.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun excludedAppDataRootUrl(): NSURL {
    val fileManager = NSFileManager.defaultManager
    val appSupportUrl: NSURL = requireNotNull(
        fileManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )
    )
    val rootUrl: NSURL = requireNotNull(
        appSupportUrl.URLByAppendingPathComponent("SonnetData", isDirectory = true)
    )
    fileManager.createDirectoryAtURL(
        url = rootUrl,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
    rootUrl.setResourceValue(
        value = NSNumber(bool = true),
        forKey = NSURLIsExcludedFromBackupKey,
        error = null
    )
    return rootUrl
}

@OptIn(ExperimentalForeignApi::class)
fun libraryDownloadDirectory(): Path {
    val rootUrl = excludedAppDataRootUrl()
    val booksUrl: NSURL = requireNotNull(
        rootUrl.URLByAppendingPathComponent("books", isDirectory = true)
    )
    NSFileManager.defaultManager.createDirectoryAtURL(
        url = booksUrl,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
    return requireNotNull(booksUrl.path).toPath()
}
