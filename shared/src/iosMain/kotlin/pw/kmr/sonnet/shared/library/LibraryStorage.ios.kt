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

@OptIn(ExperimentalForeignApi::class)
fun libraryDownloadDirectory(): Path {
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
    val booksUrl: NSURL = requireNotNull(
        appSupportUrl.URLByAppendingPathComponent("books", isDirectory = true)
    )
    fileManager.createDirectoryAtURL(
        url = booksUrl,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
    booksUrl.setResourceValue(
        value = NSNumber(bool = true),
        forKey = NSURLIsExcludedFromBackupKey,
        error = null
    )
    return requireNotNull(booksUrl.path).toPath()
}
