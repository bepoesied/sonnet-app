package pw.kmr.sonnet.shared.library

fun interface LocalLibraryCleaner {
    suspend fun clearLocalLibrary()
}
