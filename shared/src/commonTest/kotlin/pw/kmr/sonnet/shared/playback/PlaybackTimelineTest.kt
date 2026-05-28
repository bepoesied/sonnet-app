package pw.kmr.sonnet.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pw.kmr.sonnet.shared.model.DownloadedBook
import pw.kmr.sonnet.shared.model.DownloadedChapter

class PlaybackTimelineTest {

    private val book = DownloadedBook(
        id = "book-1",
        title = "Book",
        author = "Author",
        coverFilePath = null,
        chapters = listOf(
            DownloadedChapter(
                id = "chapter-1",
                title = "Chapter 1",
                position = 1,
                audioFilePath = "/tmp/1.mp3",
                durationMs = 1_000L
            ),
            DownloadedChapter(
                id = "chapter-2",
                title = "Chapter 2",
                position = 2,
                audioFilePath = "/tmp/2.mp3",
                durationMs = 2_000L
            ),
            DownloadedChapter(
                id = "chapter-3",
                title = "Chapter 3",
                position = 3,
                audioFilePath = "/tmp/3.mp3",
                durationMs = null
            )
        )
    )

    @Test
    fun seekTargetForMapsAcrossChapterBoundaries() {
        assertEquals(PlaybackSeekTarget(0, 0L), book.seekTargetFor(-50L))
        assertEquals(PlaybackSeekTarget(0, 999L), book.seekTargetFor(999L))
        assertEquals(PlaybackSeekTarget(1, 0L), book.seekTargetFor(1_000L))
        assertEquals(PlaybackSeekTarget(1, 1_500L), book.seekTargetFor(2_500L))
        assertEquals(PlaybackSeekTarget(2, 0L), book.seekTargetFor(3_000L))
        assertEquals(PlaybackSeekTarget(2, 500L), book.seekTargetFor(3_500L))
    }

    @Test
    fun positionForReturnsAbsolutePositionAndClampsKnownDurations() {
        assertEquals(0L, book.positionFor("chapter-1", -100L))
        assertEquals(1_250L, book.positionFor("chapter-2", 250L))
        assertEquals(3_000L, book.positionFor("chapter-3", 0L))
        assertEquals(3_750L, book.positionFor("chapter-3", 750L))
        assertEquals(3_000L, book.positionFor("chapter-2", 5_000L))
        assertNull(book.positionFor("missing", 100L))
    }

    @Test
    fun progressAtAndChapterMetadataHandleBoundariesAndEndOfBook() {
        assertEquals(ChapterPlaybackProgress("chapter-1", 0L), book.progressAt(0L))
        assertEquals(ChapterPlaybackProgress("chapter-2", 0L), book.progressAt(1_000L))
        assertEquals(ChapterPlaybackProgress("chapter-2", 1_999L), book.progressAt(2_999L))
        assertEquals(ChapterPlaybackProgress("chapter-3", 0L), book.progressAt(3_000L))
        assertEquals(ChapterPlaybackProgress("chapter-3", 50L), book.progressAt(3_050L))

        assertEquals("Chapter 1", book.chapterTitleAt(999L))
        assertEquals("Chapter 2", book.chapterTitleAt(1_000L))
        assertEquals("Chapter 3", book.chapterTitleAt(9_999L))
        assertEquals(0L, book.chapterOffsetAt(3_000L))
        assertEquals(250L, book.chapterOffsetAt(3_250L))
    }

    @Test
    fun totalDurationAndProjectionUseKnownDurationsOnly() {
        assertEquals(3_000L, book.totalDurationMs())
        assertEquals(0L, book.chapterStartPositionMs(0))
        assertEquals(1_000L, book.chapterStartPositionMs(1))
        assertEquals(3_000L, book.chapterStartPositionMs(2))
        assertEquals(3_000L, book.chapterStartPositionMs(3))
        assertEquals(1_500L, book.bookPositionFor(1, 500L))

        assertEquals(
            listOf(
                ProjectedChapter("chapter-1", "Chapter 1", 1, 0L, 1_000L),
                ProjectedChapter("chapter-2", "Chapter 2", 2, 1_000L, 2_000L),
                ProjectedChapter("chapter-3", "Chapter 3", 3, 3_000L, null)
            ),
            book.projectChapters()
        )
    }
}