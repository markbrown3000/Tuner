package com.titanejstudios.backupguide

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Backup TV guide – personal use only.
 *
 * With "Use extra guide sources" on, after your provider's guide is saved this fills channels with
 * no listings, and gaps in their listings, from free guide sources. Channels are matched by guide
 * ID first, then by a cleaned-up name ("BBC One HD" and "BBC 1" are the same). The source that
 * worked for each channel is remembered, and a wrong match can be fixed by hand.
 *
 * This is the TV app's side (its settings on the device); the matching itself is in
 * [BackupGuideCore], shared with the PC app. To remove it, see README.md in this module.
 */
object BackupGuide {
    val SOURCES get() = BackupGuideCore.SOURCES

    fun cleanName(name: String): String = BackupGuideCore.cleanName(name)
    fun sourceName(key: String): String = BackupGuideCore.sourceName(key)

    // ---------------- Settings ----------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("backup_guide", Context.MODE_PRIVATE)

    /** "Use extra guide sources" – off unless you turn it on. */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).putLong("at", System.currentTimeMillis()).apply()

    /** Your own extra guide addresses, used after the built-in sources. */
    fun customUrls(ctx: Context): List<String> = readList(prefs(ctx).getString("custom", null))
    fun setCustomUrls(ctx: Context, urls: List<String>) =
        prefs(ctx).edit().putString("custom", JSONArray(urls).toString()).putLong("at", System.currentTimeMillis()).apply()

    /**
     * Matches you set by hand: channel id → "source key|guide channel id", or "off" for "don't
     * use a backup guide for this channel".
     */
    fun overrides(ctx: Context): Map<String, String> = readMap(prefs(ctx).getString("overrides", null))
    fun setOverride(ctx: Context, channelId: String, value: String?) {
        val m = overrides(ctx).toMutableMap()
        if (value == null) m.remove(channelId) else m[channelId] = value
        prefs(ctx).edit().putString("overrides", JSONObject(m as Map<*, *>).toString()).putLong("at", System.currentTimeMillis()).apply()
    }

    /** What worked last time: channel id → "source key|guide channel id". */
    fun remembered(ctx: Context): Map<String, String> = readMap(prefs(ctx).getString("remembered", null))


    /** Every source's channels as last read (for choosing a match by hand). */
    fun sourceChannels(ctx: Context, key: String): List<BackupGuideCore.SourceChannel> =
        runCatching { BackupGuideCore.channelsFromJson(File(dir(ctx), "$key.json").readText()) }.getOrDefault(emptyList())

    /** The sources in use, built-in then your own. */
    fun sources(ctx: Context): List<BackupGuideCore.Source> = BackupGuideCore.sources(customUrls(ctx))

    /** Settings for your other devices (Google Drive sync), and taking theirs. */
    fun exportSettings(ctx: Context): JSONObject = JSONObject()
        .put("at", prefs(ctx).getLong("at", 0L))
        .put("enabled", enabled(ctx))
        .put("custom", JSONArray(customUrls(ctx)))
        .put("overrides", JSONObject(overrides(ctx) as Map<*, *>))

    fun importSettings(ctx: Context, o: JSONObject): Boolean {
        val at = o.optLong("at")
        if (at <= prefs(ctx).getLong("at", 0L)) return false
        prefs(ctx).edit()
            .putBoolean("enabled", o.optBoolean("enabled"))
            .putString("custom", o.optJSONArray("custom")?.toString() ?: "[]")
            .putString("overrides", o.optJSONObject("overrides")?.toString() ?: "{}")
            .putLong("at", at)
            .apply()
        return true
    }

    /** Fills gaps in the guide (see [BackupGuideCore.fill]), when it's turned on. */
    fun fill(
        ctx: Context,
        channels: List<BackupGuideCore.Channel>,
        from: Long,
        to: Long,
        open: (String) -> InputStream,
        covered: (String) -> LongArray?,
        add: (String, BackupGuideCore.Programme) -> Unit,
        log: (String) -> Unit,
    ): Int {
        if (!enabled(ctx)) return 0
        val settings = BackupGuideCore.Settings(customUrls(ctx), overrides(ctx), remembered(ctx).toMutableMap())
        val n = BackupGuideCore.fill(
            settings, channels, from, to, open, covered, add, log,
            newParser = { Xml.newPullParser() },
            saveChannels = { key, list -> if (list.isNotEmpty()) runCatching { File(dir(ctx), "$key.json").writeText(BackupGuideCore.channelsJson(list)) } },
        )
        prefs(ctx).edit().putString("remembered", JSONObject(settings.remembered as Map<*, *>).toString()).apply()
        return n
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "backup_guide").apply { mkdirs() }

    private fun readList(s: String?): List<String> = BackupGuideCore.readList(s)
    private fun readMap(s: String?): Map<String, String> = BackupGuideCore.readMap(s)
}
