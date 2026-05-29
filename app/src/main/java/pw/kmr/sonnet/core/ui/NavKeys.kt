package pw.kmr.sonnet.core.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object LoginKey : NavKey

@Serializable
data object LibraryKey : NavKey

@Serializable
data class FullPlayerKey(val bookId: String, val isDownloaded: Boolean) : NavKey
