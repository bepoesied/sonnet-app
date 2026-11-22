package pw.kmr.sonnet.core.ui.component

import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.io.File
import java.util.Locale
import pw.kmr.sonnet.R

@Composable
fun BookCover(
    bookId: String?,
    title: String,
    localCoverPath: String? = null,
    remoteCoverUrl: String? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    monogramStyle: TextStyle = MaterialTheme.typography.titleLarge,
    crossfade: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val normalizedBookId = bookId?.takeIf { it.isNotBlank() }
    val normalizedLocalPath = localCoverPath?.takeIf { it.isNotBlank() }
    val normalizedRemoteUrl = remoteCoverUrl?.takeIf { it.isNotBlank() }

    val imageRequest = remember(context, normalizedBookId, normalizedLocalPath, normalizedRemoteUrl, crossfade) {
        val localFile = normalizedLocalPath?.let { path ->
            if (path.startsWith("file://")) {
                File(path.toUri().path ?: path)
            } else {
                File(path)
            }
        }
        val data: Any? = localFile ?: normalizedRemoteUrl
        if (data == null) {
            null
        } else {
            val isLocal = localFile != null
            val cacheKey = normalizedBookId?.let { "book-cover-$it" }
                ?: localFile?.absolutePath
                ?: normalizedRemoteUrl
            ImageRequest.Builder(context)
                .data(data)
                .memoryCacheKey(cacheKey)
                .apply {
                    if (isLocal) {
                        diskCachePolicy(CachePolicy.DISABLED)
                    } else {
                        diskCacheKey(cacheKey)
                    }
                    if (crossfade) {
                        crossfade(true)
                    }
                }
                .build()
        }
    }

    var loadFailed by remember(imageRequest) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest == null || loadFailed) {
            Text(
                text = title.firstOrNull()?.toString()?.uppercase(Locale.ROOT) ?: "?",
                style = monogramStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(
                model = imageRequest,
                contentDescription = if (title.isNotBlank()) {
                    stringResource(R.string.book_cover_named_cd, title)
                } else {
                    stringResource(R.string.book_cover_generic_cd)
                },
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
