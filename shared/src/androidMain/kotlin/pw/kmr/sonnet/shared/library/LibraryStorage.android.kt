package pw.kmr.sonnet.shared.library

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath

fun libraryDownloadDirectory(context: Context): Path =
    context.applicationContext.filesDir.resolve("books").absolutePath.toPath()