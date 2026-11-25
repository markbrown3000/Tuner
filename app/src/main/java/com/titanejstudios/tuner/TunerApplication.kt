package com.titanejstudios.tuner

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.titanejstudios.tuner.data.AppLog

class TunerApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Safe mode must know about earlier crashes before anything else starts.
        com.titanejstudios.tuner.data.SafeMode.onAppStart(this)
        AppLog.init(this)
        if (com.titanejstudios.tuner.data.SafeMode.active) AppLog.info("Started in safe mode")
    }

    /** Logos and programme pictures are cached on disk so they appear instantly next time. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                // Posters and logos kept between starts (Home appears with its pictures straight away).
                .maxSizeBytes(if ((cacheDir.usableSpace) > 4L * 1024 * 1024 * 1024) 400L * 1024 * 1024 else 150L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false) // many IPTV logo hosts send "no-cache"
        .crossfade(false)
        .build()
}
