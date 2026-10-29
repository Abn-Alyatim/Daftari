package com.daftari.app.importer

import android.database.sqlite.SQLiteDatabase
import com.daftari.app.chat.ArabicText
import java.io.File

/**
 * A database file from another app (.db / .sqlite): every table becomes a [SourceTable]. When a transactions table points
 * at a customers table (customer_id / client_id …), the customer's name (and phone) are joined in, so it imports directly.
 */
object SqliteSource {
    fun isSqlite(b: ByteArray) = b.size > 16 && String(b, 0, 15, Charsets.ISO_8859_1) == "SQLite format 3"

    fun read(fileName: String, bytes: ByteArray, tmpDir: File): List<SourceTable> {
        val f = File(tmpDir, "import_${System.nanoTime()}.db")
        f.writeBytes(bytes)
        try {
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                val names = ArrayList<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')", null).use { c ->
                    while (c.moveToNext()) names += c.getString(0)
                }
                fun cols(t: String): List<String> = db.rawQuery("PRAGMA table_info(`$t`)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                fun rows(t: String, limit: Int = 20_000): List<List<String>> = db.rawQuery("SELECT * FROM `$t` LIMIT $limit", null).use { c ->
                    buildList { while (c.moveToNext()) add((0 until c.columnCount).map { i -> if (c.isNull(i)) "" else runCatching { c.getString(i) }.getOrDefault("") }) }
                }
                val people = names.associateWith { cols(it) }
                // tables that look like customers: have a name column
                fun nameCol(cs: List<String>) = cs.firstOrNull { StatementImporter.headerField(it) == Field.NAME }
                val out = ArrayList<SourceTable>()
                for (t in names) {
                    val cs = people[t].orEmpty()
                    var header = cs
                    var data = rows(t)
                    val fk = cs.indexOfFirst { c -> ArabicText.normalize(c).let { Regex("(customer|client|party|account|person|contact|user)_?id").matches(it) } }
                    if (fk >= 0 && nameCol(cs) == null) {
                        val stem = Regex("(customer|client|party|account|person|contact|user)").find(cs[fk].lowercase())!!.value
                        val target = names.firstOrNull { it != t && it.lowercase().contains(stem) && nameCol(people[it].orEmpty()) != null }
                        if (target != null) {
                            val tc = people[target].orEmpty()
                            val idCol = tc.indexOfFirst { it.equals("id", true) || it.equals("_id", true) || it.equals("${stem}_id", true) }.takeIf { it >= 0 } ?: 0
                            val nCol = tc.indexOf(nameCol(tc)); val pCol = tc.indexOfFirst { StatementImporter.headerField(it) == Field.PHONE }
                            val map = rows(target).associate { r -> r[idCol] to (r[nCol] to (if (pCol >= 0) r[pCol] else "")) }
                            header = listOf("الاسم", "الجوال") + cs
                            data = data.map { r -> val (nm, ph) = map[r[fk]] ?: ("" to ""); listOf(nm, ph) + r }
                        }
                    }
                    if (data.isNotEmpty()) out += SourceTable(fileName, t, "", header, data)
                }
                // the most useful table first: has amounts and names, then most rows
                return out.sortedWith(compareByDescending<SourceTable> { st ->
                    val g = StatementImporter.guess(st).fields
                    (if (Field.NAME in g) 2 else 0) + (if (g.any { it in setOf(Field.AMOUNT, Field.DEBIT, Field.CREDIT, Field.BALANCE) }) 2 else 0) + (if (Field.DATE in g) 1 else 0)
                }.thenByDescending { it.rows.size })
            }
        } finally { f.delete() }
    }
}
