package pw.kmr.sonnet.shared.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class SonnetDatabaseMigrationsTest {

    @Test
    fun `migration from v1 to v5 succeeds and preserves existing data`() {
        withInMemoryDatabase { connection ->
            createV1Schema(connection)
            connection.execSQL(
                """
                INSERT INTO library_items (id, title, author, coverImageUrl, updatedAtEpochMillis)
                VALUES ('book-1', 'Dune', 'Frank Herbert', 'https://example.test/cover.jpg', 1000)
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO downloads (libraryItemId, localFilePath, downloadedBytes, totalBytes, state)
                VALUES ('book-1', '/tmp/book-1', 512, 1024, 'DOWNLOADING')
                """.trimIndent()
            )

            MIGRATION_1_2.migrate(connection)
            MIGRATION_2_3.migrate(connection)
            MIGRATION_3_4.migrate(connection)
            MIGRATION_4_5.migrate(connection)

            assertV5SchemaColumns(connection)

            connection.prepare("SELECT title, isCompleted FROM library_items WHERE id = 'book-1'").use { stmt ->
                assertEquals(true, stmt.step())
                assertEquals("Dune", stmt.getText(0))
                assertEquals(0L, stmt.getLong(1))
            }
            connection.prepare("SELECT state, errorMessage FROM downloads WHERE libraryItemId = 'book-1'").use { stmt ->
                assertEquals(true, stmt.step())
                assertEquals("DOWNLOADING", stmt.getText(0))
                assertEquals(true, stmt.isNull(1))
            }
        }
    }

    @Test
    fun `migration from v2 to v5 succeeds and preserves existing data`() {
        withInMemoryDatabase { connection ->
            createV2Schema(connection)
            connection.execSQL(
                """
                INSERT INTO library_items (id, title, author, coverImageUrl, isCompleted, updatedAtEpochMillis)
                VALUES ('book-2', 'Foundation', 'Isaac Asimov', NULL, 1, 2000)
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO downloads (libraryItemId, localFilePath, downloadedBytes, totalBytes, state)
                VALUES ('book-2', '/tmp/book-2', 1024, 1024, 'COMPLETED')
                """.trimIndent()
            )

            MIGRATION_2_3.migrate(connection)
            MIGRATION_3_4.migrate(connection)
            MIGRATION_4_5.migrate(connection)

            assertV5SchemaColumns(connection)

            connection.prepare("SELECT title, isCompleted FROM library_items WHERE id = 'book-2'").use { stmt ->
                assertEquals(true, stmt.step())
                assertEquals("Foundation", stmt.getText(0))
                assertEquals(1L, stmt.getLong(1))
            }
        }
    }

    @Test
    fun `migration from v3 to v5 succeeds and preserves playback progress`() {
        withInMemoryDatabase { connection ->
            createV3Schema(connection)
            connection.execSQL(
                """
                INSERT INTO playback_progress (libraryItemId, positionMillis, durationMillis, updatedAtEpochMillis, pendingSync)
                VALUES ('book-3', 45000, 120000, 3000, 1)
                """.trimIndent()
            )

            MIGRATION_3_4.migrate(connection)
            MIGRATION_4_5.migrate(connection)

            assertV5SchemaColumns(connection)

            connection.prepare(
                """
                SELECT chapterId, chapterOffsetMillis, positionMillis, durationMillis, updatedAtEpochMillis, isCompleted, pendingSync
                FROM playback_progress
                WHERE libraryItemId = 'book-3'
                """.trimIndent()
            ).use { stmt ->
                assertEquals(true, stmt.step())
                assertEquals(true, stmt.isNull(0))
                assertEquals(0L, stmt.getLong(1))
                assertEquals(45000L, stmt.getLong(2))
                assertEquals(120000L, stmt.getLong(3))
                assertEquals(3000L, stmt.getLong(4))
                assertEquals(0L, stmt.getLong(5))
                assertEquals(1L, stmt.getLong(6))
            }
        }
    }

    @Test
    fun `migration from clean v4 to v5 adds isCompleted column`() {
        withInMemoryDatabase { connection ->
            createV4Schema(connection, includeIsCompleted = false)
            connection.execSQL(
                """
                INSERT INTO playback_progress (libraryItemId, chapterId, chapterOffsetMillis, positionMillis, durationMillis, updatedAtEpochMillis, pendingSync)
                VALUES ('book-4', 'ch-2', 1200, 6200, 90000, 4000, 0)
                """.trimIndent()
            )

            MIGRATION_4_5.migrate(connection)

            assertV5SchemaColumns(connection)

            connection.prepare(
                "SELECT chapterId, chapterOffsetMillis, positionMillis, isCompleted FROM playback_progress WHERE libraryItemId = 'book-4'"
            ).use { stmt ->
                assertEquals(true, stmt.step())
                assertEquals("ch-2", stmt.getText(0))
                assertEquals(1200L, stmt.getLong(1))
                assertEquals(6200L, stmt.getLong(2))
                assertEquals(0L, stmt.getLong(3))
            }
        }
    }

    @Test
    fun `migration from pre-existing v4 with isCompleted column succeeds without throwing`() {
        withInMemoryDatabase { connection ->
            createV4Schema(connection, includeIsCompleted = true)
            connection.execSQL(
                """
                INSERT INTO playback_progress (libraryItemId, chapterId, chapterOffsetMillis, positionMillis, durationMillis, updatedAtEpochMillis, isCompleted, pendingSync)
                VALUES ('book-5', 'ch-9', 9000, 90000, 90000, 5000, 1, 1)
                """.trimIndent()
            )

            MIGRATION_4_5.migrate(connection)

            assertV5SchemaColumns(connection)

            connection.prepare(
                "SELECT chapterId, isCompleted, pendingSync FROM playback_progress WHERE libraryItemId = 'book-5'"
            ).use { stmt ->
                assertEquals(true, stmt.step())
                assertEquals("ch-9", stmt.getText(0))
                assertEquals(1L, stmt.getLong(1))
                assertEquals(1L, stmt.getLong(2))
            }
        }
    }

    private fun withInMemoryDatabase(block: (SQLiteConnection) -> Unit) {
        val jdbcConnection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val connection = JdbcSQLiteConnection(jdbcConnection)
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun createV1Schema(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE library_items (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT,
                coverImageUrl TEXT,
                updatedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE downloads (
                libraryItemId TEXT NOT NULL PRIMARY KEY,
                localFilePath TEXT,
                downloadedBytes INTEGER NOT NULL,
                totalBytes INTEGER,
                state TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createV2Schema(connection: SQLiteConnection) {
        createV1Schema(connection)
        MIGRATION_1_2.migrate(connection)
    }

    private fun createV3Schema(connection: SQLiteConnection) {
        createV2Schema(connection)
        MIGRATION_2_3.migrate(connection)
    }

    private fun createV4Schema(connection: SQLiteConnection, includeIsCompleted: Boolean) {
        createV3Schema(connection)
        connection.execSQL("DROP TABLE playback_progress")
        if (includeIsCompleted) {
            connection.execSQL(
                """
                CREATE TABLE playback_progress (
                    libraryItemId TEXT NOT NULL PRIMARY KEY,
                    chapterId TEXT,
                    chapterOffsetMillis INTEGER NOT NULL DEFAULT 0,
                    positionMillis INTEGER NOT NULL DEFAULT 0,
                    durationMillis INTEGER,
                    updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0,
                    isCompleted INTEGER NOT NULL DEFAULT 0,
                    pendingSync INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        } else {
            connection.execSQL(
                """
                CREATE TABLE playback_progress (
                    libraryItemId TEXT NOT NULL PRIMARY KEY,
                    chapterId TEXT,
                    chapterOffsetMillis INTEGER NOT NULL DEFAULT 0,
                    positionMillis INTEGER NOT NULL DEFAULT 0,
                    durationMillis INTEGER,
                    updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0,
                    pendingSync INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private fun assertV5SchemaColumns(connection: SQLiteConnection) {
        assertEquals(
            setOf("id", "title", "author", "coverImageUrl", "isCompleted", "updatedAtEpochMillis"),
            tableColumns(connection, "library_items")
        )
        assertEquals(
            setOf("libraryItemId", "localFilePath", "downloadedBytes", "totalBytes", "state", "errorMessage"),
            tableColumns(connection, "downloads")
        )
        assertEquals(
            setOf("id", "title", "author", "narrator", "description", "coverFilePath", "isCompleted", "downloadedAtEpochMillis"),
            tableColumns(connection, "downloaded_books")
        )
        assertEquals(
            setOf("bookId", "id", "title", "position", "startMs", "endMs", "durationMs", "mediaAssetId", "audioFilePath", "contentType"),
            tableColumns(connection, "downloaded_chapters")
        )
        assertEquals(
            setOf(
                "libraryItemId",
                "chapterId",
                "chapterOffsetMillis",
                "positionMillis",
                "durationMillis",
                "updatedAtEpochMillis",
                "isCompleted",
                "pendingSync"
            ),
            tableColumns(connection, "playback_progress")
        )
    }

    private fun tableColumns(connection: SQLiteConnection, tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        connection.prepare("PRAGMA table_info($tableName)").use { stmt ->
            while (stmt.step()) {
                columns += stmt.getText(1)
            }
        }
        return columns
    }
}

private class JdbcSQLiteConnection(
    private val connection: Connection
) : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement =
        JdbcSQLiteStatement(connection.prepareStatement(sql))

    override fun close() {
        connection.close()
    }
}

private class JdbcSQLiteStatement(
    private val statement: PreparedStatement
) : SQLiteStatement {
    private var executed = false
    private var resultSet: ResultSet? = null

    override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
    override fun bindDouble(index: Int, value: Double) = statement.setDouble(index, value)
    override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
    override fun bindText(index: Int, value: String) = statement.setString(index, value)
    override fun bindNull(index: Int) = statement.setNull(index, Types.NULL)

    override fun getBlob(index: Int): ByteArray = requireResultSet().getBytes(index + 1)
    override fun getDouble(index: Int): Double = requireResultSet().getDouble(index + 1)
    override fun getLong(index: Int): Long = requireResultSet().getLong(index + 1)
    override fun getText(index: Int): String = requireResultSet().getString(index + 1).orEmpty()

    override fun isNull(index: Int): Boolean {
        val rs = requireResultSet()
        return rs.getObject(index + 1) == null
    }

    override fun getColumnCount(): Int = requireResultSet().metaData.columnCount
    override fun getColumnName(index: Int): String = requireResultSet().metaData.getColumnName(index + 1)
    override fun getColumnType(index: Int): Int = requireResultSet().metaData.getColumnType(index + 1)

    override fun step(): Boolean {
        if (!executed) {
            executed = true
            val hasResultSet = statement.execute()
            if (!hasResultSet) return false
            resultSet = statement.resultSet
        }
        return resultSet?.next() == true
    }

    override fun reset() {
        resultSet?.close()
        resultSet = null
        executed = false
    }

    override fun clearBindings() {
        statement.clearParameters()
    }

    override fun close() {
        resultSet?.close()
        statement.close()
    }

    private fun requireResultSet(): ResultSet =
        checkNotNull(resultSet) { "No active result row; call step() first." }
}
