package tech.imitravanu.eroflix.feature.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tech.imitravanu.eroflix.core.ui.PosterPlaceholder
import tech.imitravanu.eroflix.model.TitleSummary
import java.net.HttpURLConnection
import java.net.URI

/** Public poster requests carry no source credentials. No persistent remote-image cache. */
private object Posters {
    val permits = Semaphore(2)
    val cache = object : LruCache<String, Bitmap>(16*1024*1024) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount }
    suspend fun load(url: String, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$width:$url"
        cache.get(key)?.let { return@withContext it }
        permits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val uri = runCatching { URI(url) }.getOrNull() ?: return@withPermit null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@withPermit null
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200 || connection.contentLengthLong > 4*1024*1024) return@withPermit null
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16*1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        if (output.size() + n > 4*1024*1024) return@withPermit null
                        output.write(buffer, 0, n)
                    }; output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong()*bounds.outHeight > 100_000_000) return@withPermit null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / (inSampleSize*2) >= width || bounds.outHeight / (inSampleSize*2) > width*2) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { cache.put(key, it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            finally { connection.disconnect() }
        }
    }
}
@Composable fun CatalogPoster(title: TitleSummary, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(120, 720).let { (it + 119)/120*120 }
        val bitmap by produceState<Bitmap?>(null, title.posterUrl, width) {
            value = null
            value = title.posterUrl?.let { Posters.load(it, width) }
        }
        if (bitmap == null) PosterPlaceholder(title.title, Modifier.matchParentSize())
        else Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}
