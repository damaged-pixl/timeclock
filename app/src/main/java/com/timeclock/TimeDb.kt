package com.timeclock

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Punch(val id: Long, val ts: Long, val type: String) {
    val isIn: Boolean get() = type == "in"
}

class TimeDb(context: Context) : SQLiteOpenHelper(context, "timeclock.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE punches (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, " +
                "type TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun addPunch(type: String, ts: Long): Long {
        val v = ContentValues()
        v.put("ts", ts)
        v.put("type", type)
        return writableDatabase.insert("punches", null, v)
    }

    fun allPunches(): List<Punch> {
        val out = ArrayList<Punch>()
        readableDatabase.rawQuery(
            "SELECT id, ts, type FROM punches ORDER BY ts ASC, id ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Punch(c.getLong(0), c.getLong(1), c.getString(2)))
        }
        return out
    }

    fun deletePunch(id: Long) {
        writableDatabase.delete("punches", "id = ?", arrayOf(id.toString()))
    }

    /** Correct a punch's timestamp in place (type stays put, so pairing is unchanged). */
    fun updatePunchTime(id: Long, ts: Long) {
        val v = ContentValues()
        v.put("ts", ts)
        writableDatabase.update("punches", v, "id = ?", arrayOf(id.toString()))
    }

    /** Used by CSV restore to skip rows that are already in the db. */
    fun hasPunch(ts: Long, type: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM punches WHERE ts = ? AND type = ? LIMIT 1",
            arrayOf(ts.toString(), type)
        ).use { it.moveToFirst() }

    fun clear() {
        writableDatabase.delete("punches", null, null)
    }
}
