package com.titanejstudios.tuner.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.titanejstudios.tuner.data.Channel

/**
 * Two or four channels at once. Each slot has its own small-buffer player. To keep four pictures
 * smooth on a modest TV box (the same tricks TiviMate uses):
 *  - only the selected slot decodes sound – the others have their audio switched off entirely,
 *    not just muted, so there's one audio decoder instead of four;
 *  - subtitles are off in every slot;
 *  - with four pictures, streams that offer several qualities are capped at 720p;
 *  - the players start one after another rather than all at once, and use small buffers.
 */
@OptIn(UnstableApi::class)
class MultiViewController(private val context: Context) {

    inner class Slot(val player: ExoPlayer) {
        var channel by mutableStateOf<Channel?>(null)
        var error by mutableStateOf<String?>(null)
        internal var triedHls = false
        internal var retries = 0
    }

    val slots = mutableStateListOf<Slot>()
    var audioSlot by mutableIntStateOf(0)
        private set
    private val handler = Handler(Looper.getMainLooper())

    val active: Boolean get() = slots.isNotEmpty()

    fun start(count: Int, channels: List<Channel>) {
        release()
        repeat(count) {
            val p = PlayerFactory.build(context, bufferBytes = 10 * 1024 * 1024, longPause = false, multiView = true)
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, it != 0)
                .apply { if (count > 2) setMaxVideoSize(1280, 720) }
                .build()
            val slot = Slot(p)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = onError(slot, error)
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        slot.error = null
                        slot.retries = 0
                    }
                }
            })
            slots += slot
        }
        audioSlot = 0
        // Start the pictures a moment apart so the box isn't opening four streams at once.
        channels.take(count).forEachIndexed { i, c ->
            if (i == 0) setChannel(i, c) else handler.postDelayed({ if (slots.size > i && slots[i].channel == null) setChannel(i, c) }, 450L * i)
        }
    }

    fun setChannel(index: Int, channel: Channel) {
        val slot = slots.getOrNull(index) ?: return
        slot.channel = channel
        slot.error = null
        slot.triedHls = false
        slot.retries = 0
        slot.player.setMediaItem(PlayerFactory.item(channel.url, false))
        slot.player.prepare()
        slot.player.play()
    }

    /** Sound follows the highlighted picture: switch audio on there and off everywhere else. */
    fun selectAudio(index: Int) {
        if (index == audioSlot && slots.getOrNull(index)?.player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_AUDIO) == false) return
        audioSlot = index
        slots.forEachIndexed { i, s ->
            val p = s.player
            val off = i != index
            if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) != off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, off).build()
            }
            p.volume = if (off) 0f else 1f
        }
    }

    private fun onError(slot: Slot, e: PlaybackException) {
        val ch = slot.channel ?: return
        val code = e.errorCode
        when {
            code in 4000..4999 -> slot.error = "Your TV can't decode this many streams at once"
            code in 3000..3999 && !slot.triedHls && !ch.url.contains(".m3u8", true) -> {
                slot.triedHls = true
                slot.player.setMediaItem(PlayerFactory.item(ch.url, true))
                slot.player.prepare()
                slot.player.play()
            }
            slot.retries < 5 -> {
                slot.retries++
                slot.error = "Reconnecting…"
                handler.postDelayed({
                    if (slot.channel == ch) {
                        slot.player.prepare()
                        slot.player.play()
                    }
                }, 2_000L * slot.retries)
            }
            else -> slot.error = "Can't play this channel"
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        slots.forEach { it.player.release() }
        slots.clear()
        audioSlot = 0
    }
}
