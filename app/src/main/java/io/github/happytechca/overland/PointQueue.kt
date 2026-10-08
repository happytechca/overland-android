package io.github.happytechca.overland

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Points waiting to be uploaded, stored as ready-to-send GeoJSON Feature strings. */
class PointQueue private constructor(context: Context) :
    SQLiteOpenHelper(context, "points.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, feature TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(feature: String) {
        writableDatabase.insert("points", null, ContentValues().apply { put("feature", feature) })
    }

    /** Oldest points first: list of (id, feature). */
    fun oldest(limit: Int): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT id, feature FROM points ORDER BY id LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }

    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.delete("points", "id IN (${ids.joinToString(",")})", null)
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "points")

    companion object {
        @Volatile private var instance: PointQueue? = null

        fun get(context: Context): PointQueue =
            instance ?: synchronized(this) {
                instance ?: PointQueue(context.applicationContext).also { instance = it }
            }
    }
}
