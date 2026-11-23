package com.titanejstudios.tuner.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.titanejstudios.tuner.recording.Recorder
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Reads a file that is still being written. At the end of the data it waits for more instead
 * of stopping, until [growing] returns false. [baseOffset] lets playback start part-way in,
 * which is how rewinding within timeshift works.
 */
@OptIn(UnstableApi::class)
class GrowingFileDataSource(
    private val file: File,
    private val baseOffset: Long,
    private val growing: () -> Boolean,
) : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = RandomAccessFile(file, "r")
        r.seek(baseOffset + dataSpec.position)
        raf = r
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = raf ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val n = r.read(buffer, offset, length)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (!growing()) return C.RESULT_END_OF_INPUT
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * Records the live channel to a file from the moment you press pause, so you can resume,
 * rewind and fast-forward through it (up to [maxMs]). Keeps a time → byte index for seeking.
 */
class TimeshiftSession(private val url: String, val file: File, private val maxMs: Long = 90 * 60_000L) {
    val startWall: Long = System.currentTimeMillis()
    private val stopFlag = AtomicBoolean(false)
    private val progress = Recorder.Progress()
    private val index = ArrayList<LongArray>() // [wall time, bytes written by then]

    @Volatile
    var recording = false
        private set

    /** Starts recording. Returns false if there isn't room or the file can't be created. */
    fun start(): Boolean {
        val dir = file.parentFile ?: return false
        dir.mkdirs()
        file.delete()
        if (dir.usableSpace < 500L * 1024 * 1024) return false
        if (!runCatching { file.createNewFile() }.getOrDefault(false)) return false
        recording = true
        synchronized(index) { index += longArrayOf(startWall, 0L) }
        thread(isDaemon = true, name = "timeshift-rec") {
            try {
                Recorder.record(url, file, startWall + maxMs, { stopFlag.get() || dir.usableSpace < 200L * 1024 * 1024 }, progress)
            } catch (e: Exception) {
                // fall through – playback simply reaches the end of what was saved
            }
            recording = false
        }
        thread(isDaemon = true, name = "timeshift-index") {
            while (recording) {
                synchronized(index) { index += longArrayOf(System.currentTimeMillis(), progress.bytes) }
                Thread.sleep(500)
            }
        }
        return true
    }

    /** Byte offset (aligned to a TS packet) of what was arriving at [wall]. */
    fun offsetFor(wall: Long): Long = synchronized(index) {
        val e = index.lastOrNull { it[0] <= wall } ?: return 0L
        e[1] - e[1] % 188
    }

    /** When the byte at [offset] arrived. */
    fun wallFor(offset: Long): Long = synchronized(index) {
        index.firstOrNull { it[1] >= offset }?.get(0) ?: (index.lastOrNull()?.get(0) ?: startWall)
    }

    /** Latest time that's safely on disk. */
    val newestWall: Long get() = synchronized(index) { index.lastOrNull()?.get(0) ?: startWall }

    fun stop() {
        stopFlag.set(true)
        recording = false
        thread(isDaemon = true) {
            Thread.sleep(1_500)
            file.delete()
        }
    }
}
