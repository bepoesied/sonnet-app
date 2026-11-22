package pw.kmr.sonnet.shared.playback

import pw.kmr.sonnet.shared.model.DownloadedBook

data class PlaybackSeekTarget(
    val chapterIndex: Int,
    val chapterPositionMs: Long
)

data class ChapterPlaybackProgress(
    val chapterId: String,
    val chapterOffsetMs: Long
)

data class ProjectedChapter(
    val id: String,
    val title: String,
    val position: Int,
    val startPositionMs: Long,
    val durationMs: Long?
)

fun DownloadedBook.seekTargetFor(bookPositionMs: Long): PlaybackSeekTarget {
    return resolveChapterTarget(bookPositionMs) ?: PlaybackSeekTarget(0, 0L)
}

fun DownloadedBook.positionFor(chapterId: String, chapterOffsetMs: Long): Long? {
    val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
    if (chapterIndex < 0) return null
    val beforeChapter = chapterStartPositionMs(chapterIndex)
    val chapterDuration = chapters[chapterIndex].durationMs
    val offset = if (chapterDuration == null || chapterDuration <= 0L) {
        chapterOffsetMs.coerceAtLeast(0L)
    } else {
        chapterOffsetMs.coerceIn(0L, chapterDuration)
    }
    return beforeChapter + offset
}

fun DownloadedBook.progressAt(bookPositionMs: Long): ChapterPlaybackProgress? {
    val target = resolveChapterTarget(bookPositionMs) ?: return null
    val chapter = chapters[target.chapterIndex]
    return ChapterPlaybackProgress(chapter.id, target.chapterPositionMs)
}

fun DownloadedBook.chapterTitleAt(bookPositionMs: Long): String {
    val target = resolveChapterTarget(bookPositionMs) ?: return ""
    return chapters[target.chapterIndex].title
}

fun DownloadedBook.chapterOffsetAt(bookPositionMs: Long): Long = progressAt(bookPositionMs)?.chapterOffsetMs ?: 0L

fun DownloadedBook.totalDurationMs(): Long = chapters.sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) }

fun DownloadedBook.chapterStartPositionMs(chapterIndex: Int): Long {
    return chapters
        .take(chapterIndex.coerceAtLeast(0))
        .sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) }
}

fun DownloadedBook.bookPositionFor(chapterIndex: Int, chapterPositionMs: Long): Long {
    return chapterStartPositionMs(chapterIndex) + chapterPositionMs.coerceAtLeast(0L)
}

fun DownloadedBook.projectChapters(): List<ProjectedChapter> {
    var startPosition = 0L
    return chapters.map { chapter ->
        ProjectedChapter(
            id = chapter.id,
            title = chapter.title,
            position = chapter.position,
            startPositionMs = startPosition,
            durationMs = chapter.durationMs
        ).also { startPosition += (chapter.durationMs ?: 0L).coerceAtLeast(0L) }
    }
}

private fun DownloadedBook.resolveChapterTarget(bookPositionMs: Long): PlaybackSeekTarget? {
    if (chapters.isEmpty()) return null
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEachIndexed { index, chapter ->
        val duration = (chapter.durationMs ?: 0L).coerceAtLeast(0L)
        if (duration > 0L) {
            if (remaining < duration) return PlaybackSeekTarget(index, remaining)
            remaining -= duration
        } else {
            val hasLaterPositiveDuration = (index + 1 until chapters.size).any { (chapters[it].durationMs ?: 0L) > 0L }
            if (!hasLaterPositiveDuration || remaining == 0L) {
                return PlaybackSeekTarget(index, remaining)
            }
        }
    }
    val lastIndex = chapters.lastIndex
    val lastDuration = (chapters[lastIndex].durationMs ?: 0L).coerceAtLeast(0L)
    return PlaybackSeekTarget(lastIndex, lastDuration)
}