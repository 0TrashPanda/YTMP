package dev.trashpanda.ytmp.host

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.trashpanda.ytmp.core.RoomStore
import dev.trashpanda.ytmp.core.SavedRoom

/** The phone's rooms in its own SQLite database, so they survive app restarts and updates. */
class PhoneRoomStore(context: Context) : RoomStore {
    private val db = object : SQLiteOpenHelper(context, "ytmp.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE rooms (code TEXT PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun loadAll(): List<SavedRoom> = db.readableDatabase.query("rooms", arrayOf("code", "data"), null, null, null, null, null).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                runCatching { add(SavedRoom.fromJson(rows.getString(1))) }
                    .onFailure { Log.w(TAG, "Skipping room ${rows.getString(0)}: can't read it", it) }
            }
        }
    }

    override fun save(room: SavedRoom) {
        val values = ContentValues().apply {
            put("code", room.code)
            put("data", room.toJson())
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict("rooms", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun delete(code: String) {
        db.writableDatabase.delete("rooms", "code = ?", arrayOf(code))
    }

    private companion object {
        const val TAG = "YtmpRooms"
    }
}
