package xyz.aezdek.aezgram.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import xyz.aezdek.aezgram.client.LocalDb
import xyz.aezdek.aezgram.client.Tables

/**
 * [Tables] on SQLite: one key/value table per name. Holds what the server sent (ciphertext envelopes, public keys),
 * so the app opens instantly and works offline; plaintext only ever lives in memory.
 */
class SqliteTables(context: Context, name: String = "aezgram.db") : Tables {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            for (table in LocalDb.TABLES) db.execSQL("CREATE TABLE IF NOT EXISTS $table (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun all(table: String): List<String> = db.query(table, arrayOf("v"), null, null, null, null, null).use { cursor ->
        val out = ArrayList<String>(cursor.count)
        while (cursor.moveToNext()) out += cursor.getString(0)
        out
    }

    override fun get(table: String, key: String): String? =
        db.query(table, arrayOf("v"), "k = ?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun put(table: String, key: String, value: String) {
        db.insertWithOnConflict(table, null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun delete(table: String, key: String) {
        db.delete(table, "k = ?", arrayOf(key))
    }

    override fun transaction(block: () -> Unit) {
        val db = db
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clear() {
        val db = db
        for (table in LocalDb.TABLES) db.delete(table, null, null)
    }
}
