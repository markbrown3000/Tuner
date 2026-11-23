package com.titanejstudios.tuner.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.titanejstudios.tuner.data.AppLog

/** Another app on the TV (Plex, BBC iPlayer, Netflix…) that Tuner can open. */
class TvApp(
    val packageName: String,
    val label: String,
    /** Wide TV banner (320x180) when the app has one, otherwise null. */
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
)

object InstalledApps {
    /** Popular UK apps shown first, in this order, when installed. */
    val preferred = listOf(
        "com.plexapp.android",              // Plex
        "bbc.iplayer.android",              // BBC iPlayer
        "com.netflix.ninja",                // Netflix (TV)
        "com.google.android.youtube.tv",    // YouTube (TV)
        "com.disney.disneyplus",            // Disney+
        "com.amazon.amazonvideo.livingroom",// Prime Video (TV)
        "air.ITVMobilePlayer",              // ITVX
        "com.channel4.ondemand",            // Channel 4
        "com.channel5.my5",                 // My5
        "com.nowtv.wands",                  // NOW
        "com.apple.atve.androidtv.appletv", // Apple TV
        "uk.co.uktv.dave",                  // U (UKTV Play)
        "com.spotify.tv.android",           // Spotify
        "com.google.android.youtube.tvkids",// YouTube Kids
        "com.ar.tivimate",                  // TiviMate
    )

    /** Settings, launchers and other system bits that aren't worth showing. */
    private val skip = setOf(
        "com.android.tv.settings", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.vending", "com.google.android.play.games", "com.google.android.tv.remote.service",
    )

    /** Lists launchable apps. Slow-ish (loads pictures) – call off the main thread. */
    fun load(context: Context): List<TvApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val infos = try {
                pm.queryIntentActivities(query, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in infos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in skip || pkg in found) continue
                try {
                    val ai = ri.activityInfo.applicationInfo
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                    val banner = (ri.activityInfo.loadBanner(pm) ?: ai.loadBanner(pm))?.toImage(320, 180)
                    val icon = if (banner == null) ri.loadIcon(pm)?.toImage(144, 144) else null
                    found[pkg] = TvApp(pkg, label, banner, icon)
                } catch (e: Exception) {
                    AppLog.warn("Couldn't read app $pkg", e)
                }
            }
        }
        val order = preferred.withIndex().associate { it.value to it.index }
        return found.values.sortedWith(
            compareBy<TvApp> { order[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() }
        )
    }

    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
        if (intent == null) return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            AppLog.warn("Couldn't open $packageName", e)
            false
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun Drawable.toImage(maxW: Int, maxH: Int): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null && bitmap.width <= maxW * 2) return bitmap.asImageBitmap()
        val w0 = intrinsicWidth.takeIf { it > 0 } ?: maxW
        val h0 = intrinsicHeight.takeIf { it > 0 } ?: maxH
        val scale = minOf(maxW.toFloat() / w0, maxH.toFloat() / h0, 1f)
        val w = (w0 * scale).toInt().coerceAtLeast(1)
        val h = (h0 * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bmp.asImageBitmap()
    }
}
