package pw.kmr.sonnet.shared.library

interface BookDownloadScheduler {
    fun enqueueDownload(bookId: String, restartInProgress: Boolean = false)

    fun cancelDownload(bookId: String)
}
