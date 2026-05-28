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
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEachIndexed { index, chapter ->
        val duration = chapter.durationMs ?: 0L
        if (duration <= 0L || remaining < duration) return PlaybackSeekTarget(index, remaining)
        remaining -= duration
    }
    val lastIndex = chapters.lastIndex.coerceAtLeast(0)
    val lastDuration = chapters.getOrNull(lastIndex)?.durationMs ?: 0L
    return PlaybackSeekTarget(lastIndex, lastDuration)
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

fun DownloadedBook.progressAt(bookPositionMs: Long): ChapterPlaybackProgress {
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEach { chapter ->
        val duration = chapter.durationMs ?: 0L
        if (duration <= 0L || remaining < duration) {
            return ChapterPlaybackProgress(chapter.id, remaining)
        }
        remaining -= duration
    }
    val last = chapters.last()
    return ChapterPlaybackProgress(last.id, (last.durationMs ?: 0L).coerceAtLeast(0L))
}

fun DownloadedBook.chapterTitleAt(bookPositionMs: Long): String {
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEach { chapter ->
        val duration = chapter.durationMs ?: 0L
        if (duration <= 0L || remaining < duration) return chapter.title
        remaining -= duration
    }
    return chapters.last().title
}

fun DownloadedBook.chapterOffsetAt(bookPositionMs: Long): Long = progressAt(bookPositionMs).chapterOffsetMs

fun DownloadedBook.totalDurationMs(): Long = chapters.sumOf { it.durationMs ?: 0L }

fun DownloadedBook.chapterStartPositionMs(chapterIndex: Int): Long {
    return chapters
        .take(chapterIndex.coerceAtLeast(0))
        .sumOf { it.durationMs ?: 0L }
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
        ).also { startPosition += chapter.durationMs ?: 0L }
    }
}