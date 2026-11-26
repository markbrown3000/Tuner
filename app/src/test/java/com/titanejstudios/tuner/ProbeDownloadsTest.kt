package com.titanejstudios.tuner

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** TEMPORARY: checks the live-subtitle downloads once on CI (removed after). */
class ProbeDownloadsTest {
    @Test
    fun probe() {
        val info = StringBuilder()
        val aar = URL("https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar").readBytes()
        info.append("aar=${aar.size}; ")
        val needed = sortedSetOf<String>()
        var arm = false
        ZipInputStream(aar.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val bytes = z.readBytes()
                info.append("${e.name}=${bytes.size}; ")
                if (e.name == "jni/arm64-v8a/libvosk.so") {
                    arm = true
                    Regex("lib[A-Za-z0-9_+.-]*\\.so").findAll(String(bytes, Charsets.ISO_8859_1)).forEach { needed += it.value }
                }
            }
        }
        info.append("libs=$needed; ")
        val c = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection() as HttpURLConnection
        info.append("model=${c.responseCode} ${c.contentLengthLong}; ")
        val first = ZipInputStream(c.inputStream).use { z -> (1..6).mapNotNull { z.nextEntry?.name } }
        info.append("modelEntries=$first")
        assertTrue(info.toString(), false && arm)
    }
}
