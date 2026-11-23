package com.titanejstudios.tuner.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.titanejstudios.tuner.R
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.Store

/**
 * Publishes a "Tuner – Favourites" row and "Continue watching" entries to the TV home screen.
 * Android TV shows both; Google TV only shows what it allows for sideloaded apps, so every call
 * here is best-effort and never crashes the app.
 */
object HomeScreen {
    private const val TAG = "TunerHome"

    private fun iconUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_launcher}")

    fun channelLink(channelId: String): Uri = Uri.parse("tuner://open?channel=" + Uri.encode(channelId))
    fun vodLink(contentId: String): Uri = Uri.parse("tuner://open?vod=" + Uri.encode(contentId))

    fun publishFavourites(context: Context, store: Store, favourites: List<Channel>) {
        try {
            val helper = PreviewChannelHelper(context)
            var channelId = store.previewChannelId
            val exists = channelId > 0 && runCatching { helper.getPreviewChannel(channelId) }.getOrNull() != null
            if (!exists) {
                val logo = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                val previewChannel = PreviewChannel.Builder()
                    .setDisplayName("Tuner – Favourites")
                    .setAppLinkIntentUri(Uri.parse("tuner://open"))
                    .setLogo(logo)
                    .build()
                channelId = helper.publishDefaultChannel(previewChannel)
                store.previewChannelId = channelId
            }
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
            for (c in favourites.take(25)) {
                val program = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewProgramColumns.TYPE_CHANNEL)
                    .setTitle(c.name)
                    .setDescription(c.group)
                    .setPosterArtUri(c.logo?.let { Uri.parse(it) } ?: iconUri(context))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                    .setIntentUri(channelLink(c.id))
                    .setInternalProviderId(c.id)
                    .setLive(true)
                    .build()
                helper.publishPreviewProgram(program)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't publish favourites row", t)
        }
    }

    fun syncContinueWatching(context: Context, store: Store, entries: Collection<ResumeEntry>) {
        try {
            val helper = PreviewChannelHelper(context)
            val ids = store.watchNextIds.toMutableMap()
            val wanted = entries.associateBy { it.contentId }
            // Remove finished ones
            for ((contentId, programId) in ids.entries.toList()) {
                if (contentId !in wanted) {
                    runCatching {
                        context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(programId), null, null)
                    }
                    ids.remove(contentId)
                }
            }
            for (e in entries.sortedByDescending { it.updatedAt }.take(10)) {
                val program = WatchNextProgram.Builder()
                    .setType(
                        if (e.seriesId != null) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE
                        else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE
                    )
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(e.updatedAt)
                    .setLastPlaybackPositionMillis(e.positionMs.toInt())
                    .setDurationMillis(e.durationMs.toInt())
                    .setTitle(e.title)
                    .setDescription(e.subtitle ?: "")
                    .setPosterArtUri(e.poster?.let { Uri.parse(it) } ?: iconUri(context))
                    .setIntentUri(vodLink(e.contentId))
                    .setInternalProviderId(e.contentId)
                    .build()
                val existing = ids[e.contentId]
                if (existing != null) {
                    helper.updateWatchNextProgram(program, existing)
                } else {
                    ids[e.contentId] = helper.publishWatchNextProgram(program)
                }
            }
            store.watchNextIds = ids
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't update Continue watching", t)
        }
    }
}
