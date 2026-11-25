package android.util

/** Stand-in for Android's Base64, so the TV app's Google sign-in code runs unchanged on a PC. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    fun decode(str: String, flags: Int): ByteArray {
        val s = str.trim().replace("\n", "").replace("\r", "")
        return if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder().decode(s.trimEnd('='))
        else java.util.Base64.getMimeDecoder().decode(s)
    }

    fun encodeToString(input: ByteArray, flags: Int): String {
        var enc = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        return enc.encodeToString(input)
    }
}
