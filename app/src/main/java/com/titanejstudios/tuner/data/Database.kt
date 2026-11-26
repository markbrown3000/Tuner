package com.titanejstudios.tuner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

/**
 * SQLite store for channels and the TV guide. Guides are written straight from the XML parser,
 * so even huge guide files never have to fit in memory, and searching uses SQL instead of
 * scanning everything.
 */
class Database private constructor(context: Context) :
    SQLiteOpenHelper(context, "tuner.db", null, 4) {

    companion object {
        @Volatile
        private var instance: Database? = null

        fun get(context: Context): Database =
            instance ?: synchronized(this) {
                instance ?: Database(context.applicationContext).also { instance = it }
            }
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE channels (source_id TEXT NOT NULL, pos INTEGER NOT NULL, id TEXT NOT NULL, " +
                "name TEXT, grp TEXT, logo TEXT, url TEXT, epg_id TEXT, cu_days INTEGER, cu_tpl TEXT, cu_tz TEXT, " +
                "num INTEGER DEFAULT 0, grp_pos INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX channels_src ON channels(source_id, pos)")
        db.execSQL(
            "CREATE TABLE programmes (source_id TEXT NOT NULL, channel_id TEXT NOT NULL, start INTEGER NOT NULL, " +
                "stop INTEGER NOT NULL, title TEXT, descr TEXT, category TEXT, image TEXT, extra TEXT)"
        )
        db.execSQL("CREATE INDEX prog_ch ON programmes(channel_id, start)")
        db.execSQL("CREATE INDEX prog_src ON programmes(source_id)")
        db.execSQL("CREATE TABLE xmltv_channels (source_id TEXT NOT NULL, xml_id TEXT NOT NULL, name TEXT)")
        db.execSQL("CREATE INDEX xmltv_src ON xmltv_channels(source_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN num INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE channels ADD COLUMN grp_pos INTEGER DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Programme pictures arrive with the next guide update – no need to re-download now.
            db.execSQL("ALTER TABLE programmes ADD COLUMN image TEXT")
        }
        if (oldVersion < 4) {
            // Episode titles, numbers and NEW/premiere/final arrive with the next guide update.
            db.execSQL("ALTER TABLE programmes ADD COLUMN extra TEXT")
        }
    }

    private fun SQLiteStatement.str(i: Int, v: String?) {
        if (v == null) bindNull(i) else bindString(i, v)
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

    // ---------------- Channels ----------------

    fun replaceChannels(sourceId: String, channels: List<Channel>) {
        writableDatabase.inTransaction {
            delete("channels", "source_id = ?", arrayOf(sourceId))
            val st = compileStatement("INSERT INTO channels VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")
            channels.forEachIndexed { i, c ->
                st.clearBindings()
                st.bindString(1, sourceId)
                st.bindLong(2, i.toLong())
                st.bindString(3, c.id)
                st.str(4, c.name)
                st.str(5, c.group)
                st.str(6, c.logo)
                st.str(7, c.url)
                st.str(8, c.epgId)
                st.bindLong(9, c.catchupDays.toLong())
                st.str(10, c.catchupTemplate)
                st.str(11, c.catchupTz)
                st.bindLong(12, c.number.toLong())
                st.bindLong(13, c.groupPos.toLong())
                st.executeInsert()
            }
            st.close()
        }
    }

    fun loadChannels(sourceIds: List<String>): List<Channel> {
        val out = ArrayList<Channel>()
        for (sid in sourceIds) {
            readableDatabase.rawQuery(
                "SELECT id, name, grp, logo, url, epg_id, cu_days, cu_tpl, cu_tz, num, grp_pos FROM channels WHERE source_id = ? ORDER BY pos",
                arrayOf(sid),
            ).use { c ->
                while (c.moveToNext()) {
                    out += Channel(
                        id = c.getString(0),
                        name = c.getString(1) ?: "",
                        group = c.getString(2) ?: "",
                        logo = c.getString(3),
                        url = c.getString(4) ?: "",
                        epgId = c.getString(5),
                        sourceId = sid,
                        catchupDays = c.getInt(6),
                        catchupTemplate = c.getString(7),
                        catchupTz = c.getString(8),
                        number = c.getInt(9),
                        groupPos = c.getInt(10),
                    )
                }
            }
        }
        return out
    }

    fun hasChannels(sourceId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM channels WHERE source_id = ? LIMIT 1", arrayOf(sourceId)).use { it.moveToFirst() }

    // ---------------- Guide ----------------

    /**
     * Replaces a playlist's guide. [fill] is given functions to add programmes and XMLTV channels.
     * Everything happens in one transaction: if [fill] throws, the previous guide is kept.
     */
    fun replaceEpg(
        sourceId: String,
        fill: (addProgramme: (String, Programme) -> Unit, addXmlChannel: (String, String) -> Unit) -> Unit,
    ) {
        writableDatabase.inTransaction {
            // The new guide goes into a side table first: guide files usually only start a few hours
            // back, so the older programmes already saved (for catch-up, up to a week) are kept.
            execSQL("CREATE TEMP TABLE IF NOT EXISTS programmes_new AS SELECT * FROM programmes WHERE 0")
            execSQL("DELETE FROM programmes_new")
            delete("xmltv_channels", "source_id = ?", arrayOf(sourceId))
            val prog = compileStatement("INSERT INTO programmes_new VALUES (?,?,?,?,?,?,?,?,?)")
            val xml = compileStatement("INSERT INTO xmltv_channels VALUES (?,?,?)")
            fill(
                { channelId, p ->
                    prog.clearBindings()
                    prog.bindString(1, sourceId)
                    prog.bindString(2, channelId)
                    prog.bindLong(3, p.start)
                    prog.bindLong(4, p.stop)
                    prog.str(5, p.title)
                    prog.str(6, p.desc)
                    prog.str(7, p.category)
                    prog.str(8, p.image)
                    prog.str(9, p.extra)
                    prog.executeInsert()
                },
                { id, name ->
                    xml.clearBindings()
                    xml.bindString(1, sourceId)
                    xml.bindString(2, id)
                    xml.str(3, name)
                    xml.executeInsert()
                },
            )
            prog.close()
            xml.close()
            val now = System.currentTimeMillis()
            // Keep this source's older programmes only from before each channel's new guide starts
            // (and no older than a week); everything from then on comes from the new file.
            execSQL(
                "DELETE FROM programmes WHERE source_id = ? AND (stop < ? OR start >= COALESCE(" +
                    "(SELECT MIN(n.start) FROM programmes_new n WHERE n.channel_id = programmes.channel_id), ?))",
                arrayOf<Any>(sourceId, now - 7 * 86_400_000L, now - 3 * 3_600_000L),
            )
            execSQL("INSERT INTO programmes SELECT * FROM programmes_new")
            execSQL("DELETE FROM programmes_new")
        }
    }

    /** Loads programmes overlapping [from, to]; channels with catch-up keep older ones too. */
    fun loadEpg(
        sourceIds: List<String>,
        from: Long,
        to: Long,
        catchupFrom: Map<String, Long>,
    ): Map<String, List<Programme>> {
        if (sourceIds.isEmpty()) return emptyMap()
        val earliest = minOf(from, catchupFrom.values.minOrNull() ?: from)
        val out = HashMap<String, ArrayList<Programme>>()
        readableDatabase.rawQuery(
            "SELECT channel_id, start, stop, title, descr, category, image, extra FROM programmes " +
                "WHERE source_id IN (${placeholders(sourceIds.size)}) AND stop > ? AND start < ? ORDER BY channel_id, start",
            (sourceIds + earliest.toString() + to.toString()).toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val stop = c.getLong(2)
                if (stop <= (catchupFrom[id] ?: from)) continue
                val list = out.getOrPut(id) { ArrayList() }
                val start = c.getLong(1)
                if (list.isNotEmpty() && list.last().start == start) continue
                list += Programme(start, stop, c.getString(3) ?: "", c.getString(4), c.getString(5), c.getString(6), c.getString(7))
            }
        }
        return out
    }

    /** Programme title search across the stored guide. */
    /** Each channel's last saved programme end (for a source), so an extra guide only adds what's missing. */
    fun lastStops(sourceId: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT channel_id, MAX(stop) FROM programmes WHERE source_id = ? GROUP BY channel_id", arrayOf(sourceId)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
        }
        return out
    }

    /** Adds programmes from an extra guide (nothing is replaced). */
    fun addProgrammes(sourceId: String, fill: (addProgramme: (String, Programme) -> Unit) -> Unit): Int {
        var n = 0
        writableDatabase.inTransaction {
            val prog = compileStatement("INSERT INTO programmes VALUES (?,?,?,?,?,?,?,?,?)")
            fill { channelId, p ->
                prog.clearBindings()
                prog.bindString(1, sourceId)
                prog.bindString(2, channelId)
                prog.bindLong(3, p.start)
                prog.bindLong(4, p.stop)
                prog.str(5, p.title)
                prog.str(6, p.desc)
                prog.str(7, p.category)
                prog.str(8, p.image)
                prog.str(9, p.extra)
                prog.executeInsert()
                n++
            }
            prog.close()
        }
        return n
    }

    /** How far ahead the saved guide goes (the latest programme end), or 0. */
    fun guideEnd(sourceIds: List<String>): Long {
        if (sourceIds.isEmpty()) return 0L
        readableDatabase.rawQuery(
            "SELECT MAX(stop) FROM programmes WHERE source_id IN (${placeholders(sourceIds.size)})",
            sourceIds.toTypedArray(),
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    fun searchProgrammes(sourceIds: List<String>, query: String, after: Long, limit: Int): List<Pair<String, Programme>> {
        if (sourceIds.isEmpty()) return emptyList()
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<Pair<String, Programme>>()
        readableDatabase.rawQuery(
            "SELECT channel_id, start, stop, title, descr, category, image, extra FROM programmes " +
                "WHERE source_id IN (${placeholders(sourceIds.size)}) AND stop > ? AND title LIKE ? ESCAPE '\\' " +
                "ORDER BY start LIMIT $limit",
            (sourceIds + after.toString() + like).toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out += c.getString(0) to Programme(c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4), c.getString(5), c.getString(6), c.getString(7))
            }
        }
        return out
    }

    /**
     * Programmes between [from] and [to] whose title contains any of [titleWords] (or, when
     * [descWords] is given, whose description contains one of those). For the Christmas planner.
     */
    fun findInWindow(
        sourceIds: List<String>,
        from: Long,
        to: Long,
        titleWords: List<String>,
        descWords: List<String> = emptyList(),
        categoryWords: List<String> = emptyList(),
        limit: Int = 800,
    ): List<Pair<String, Programme>> {
        if (sourceIds.isEmpty() || (titleWords.isEmpty() && descWords.isEmpty())) return emptyList()
        fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val words = titleWords.map { "title LIKE ? ESCAPE '\\'" } + descWords.map { "descr LIKE ? ESCAPE '\\'" }
        val cats = if (categoryWords.isEmpty()) "" else " AND (" + categoryWords.joinToString(" OR ") { "category LIKE ? ESCAPE '\\'" } + ")"
        val args = sourceIds + from.toString() + to.toString() + (titleWords + descWords).map { like(it) } + categoryWords.map { like(it) }
        val out = ArrayList<Pair<String, Programme>>()
        readableDatabase.rawQuery(
            "SELECT channel_id, start, stop, title, descr, category, image, extra FROM programmes " +
                "WHERE source_id IN (${placeholders(sourceIds.size)}) AND start >= ? AND start < ? AND (" + words.joinToString(" OR ") + ")" + cats +
                " ORDER BY start LIMIT $limit",
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out += c.getString(0) to Programme(c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4), c.getString(5), c.getString(6), c.getString(7))
            }
        }
        return out
    }

    /** Sets logos only where the playlist had none. */
    fun fillMissingLogos(sourceId: String, logos: Map<String, String>) {
        writableDatabase.inTransaction {
            val st = compileStatement("UPDATE channels SET logo = ? WHERE source_id = ? AND id = ? AND (logo IS NULL OR logo = '')")
            for ((id, logo) in logos) {
                st.clearBindings()
                st.bindString(1, logo)
                st.bindString(2, sourceId)
                st.bindString(3, id)
                st.executeUpdateDelete()
            }
            st.close()
        }
    }

    fun xmltvChannels(sourceId: String): List<XmltvChannel> {
        val out = ArrayList<XmltvChannel>()
        readableDatabase.rawQuery(
            "SELECT xml_id, name FROM xmltv_channels WHERE source_id = ? ORDER BY name",
            arrayOf(sourceId),
        ).use { c ->
            while (c.moveToNext()) out += XmltvChannel(c.getString(0), c.getString(1) ?: c.getString(0))
        }
        return out
    }

    // ---------------- Housekeeping & self-test ----------------

    /**
     * Removes programmes that finished before [before] (older than any catch-up window), and every
     * so often compacts the file so it doesn't keep its high-water size. Returns rows removed.
     */
    fun tidy(before: Long, compact: Boolean): Int {
        val removed = writableDatabase.delete("programmes", "stop < ?", arrayOf(before.toString()))
        if (compact) {
            try {
                writableDatabase.execSQL("VACUUM")
            } catch (e: Exception) {
                AppLog.warn("Couldn't compact the guide database", e)
            }
        }
        return removed
    }

    fun channelCount(): Long = count("channels")
    fun programmeCount(): Long = count("programmes")

    private fun count(table: String): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun deleteSource(sourceId: String) {
        writableDatabase.inTransaction {
            delete("channels", "source_id = ?", arrayOf(sourceId))
            delete("programmes", "source_id = ?", arrayOf(sourceId))
            delete("xmltv_channels", "source_id = ?", arrayOf(sourceId))
        }
    }
}
