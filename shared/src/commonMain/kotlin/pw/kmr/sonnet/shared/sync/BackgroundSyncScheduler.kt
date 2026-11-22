package pw.kmr.sonnet.shared.sync

fun interface BackgroundSyncScheduler {
    fun scheduleProgressSync()

    companion object {
        val NoOp = BackgroundSyncScheduler {}
    }
}
