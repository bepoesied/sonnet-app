package pw.kmr.sonnet.shared.library

interface LocalLibraryCleaner {
    suspend fun clearLocalLibrary()
}
