package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Channel logos and pictures from the web, kept in memory once loaded. */
object ImageCache {
    private val cache = object : LinkedHashMap<String, ImageBitmap?>(400, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 400
    }

    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    suspend fun load(url: String): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(url)) return cache[url] }
        val img = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "Tuner")
                val bytes = c.inputStream.use { it.readBytes() }
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
        synchronized(cache) { cache[url] = img }
        return img
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    if (url.isNullOrBlank()) return
    var img by remember(url) { mutableStateOf(ImageCache.cached(url)) }
    LaunchedEffect(url) { if (img == null) img = ImageCache.load(url) }
    img?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = contentScale) }
}
