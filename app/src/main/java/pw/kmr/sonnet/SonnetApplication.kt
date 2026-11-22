package pw.kmr.sonnet

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import pw.kmr.sonnet.core.AppContainer

class SonnetApplication : Application(), SingletonImageLoader.Factory {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { appContainer.okHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR))
                    .maxSizeBytes(DISK_CACHE_MAX_SIZE_BYTES)
                    .build()
            }
            .build()
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val IMAGE_CACHE_DIR = "image_cache"
        const val DISK_CACHE_MAX_SIZE_BYTES = 100L * 1024 * 1024
    }
}
