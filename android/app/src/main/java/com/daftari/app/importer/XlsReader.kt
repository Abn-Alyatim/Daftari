package com.daftari.app.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for old Excel files (.xls, BIFF8 inside an OLE2 compound file) — no library, a few KB in the APK.
 * Reads every sheet's cells as text: shared strings, inline labels, numbers, RK/MULRK, cached formula results.
 */
object XlsReader {
    class Sheet(val name: String, val rows: List<List<String>>)

    fun isXls(b: ByteArray) = b.size > 512 && (b[0].toInt() and 0xFF) == 0xD0 && (b[1].toInt() and 0xFF) == 0xCF && (b[2].toInt() and 0xFF) == 0x11 && (b[3].toInt() and 0xFF) == 0xE0

    fun read(bytes: ByteArray): List<Sheet> = parseBiff(workbookStream(bytes))

    // ------------------------------------------------------------------ OLE2 / CFB
    private fun workbookStream(b: ByteArray): ByteArray {
        require(isXls(b)) { "not an OLE2 file" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val secSize = 1 shl bb.getShort(0x1E).toInt()
        val miniSize = 1 shl bb.getShort(0x20).toInt()
        val fatCount = bb.getInt(0x2C)
        val dirStart = bb.getInt(0x30)
        val miniCutoff = bb.getInt(0x38)
        val miniFatStart = bb.getInt(0x3C)
        var difatStart = bb.getInt(0x44)
        fun off(sec: Int) = (sec + 1).toLong() * secSize
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) { val s = bb.getInt(0x4C + i * 4); if (s >= 0) fatSectors += s }
        var guard = 0
        while (difatStart >= 0 && fatSectors.size < fatCount && guard++ < 10_000) {
            val o = off(difatStart).toInt()
            for (i in 0 until secSize / 4 - 1) { val s = bb.getInt(o + i * 4); if (s >= 0) fatSectors += s }
            difatStart = bb.getInt(o + secSize - 4)
        }
        val fat = IntArray(fatSectors.size * secSize / 4)
        fatSectors.forEachIndexed { k, s -> val o = off(s).toInt(); for (i in 0 until secSize / 4) if (o + i * 4 + 4 <= b.size) fat[k * secSize / 4 + i] = bb.getInt(o + i * 4) }
        fun chain(start: Int): List<Int> { val out = ArrayList<Int>(); var s = start; var n = 0; while (s >= 0 && s < fat.size && n++ < 1_000_000) { out += s; s = fat[s] }; return out }
        fun readChain(start: Int, size: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (s in chain(start)) { val o = off(s).toInt(); out.write(b, o, minOf(secSize, b.size - o)) }
            val all = out.toByteArray(); return if (size in 0..all.size.toLong()) all.copyOf(size.toInt()) else all
        }
        val dir = readChain(dirStart, -1)
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        data class E(val name: String, val type: Int, val start: Int, val size: Long)
        val entries = (0 until dir.size / 128).map { i ->
            val o = i * 128
            val nameLen = (db.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dir, o, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
            E(name, dir[o + 0x42].toInt(), db.getInt(o + 0x74), db.getInt(o + 0x78).toLong() and 0xFFFFFFFFL)
        }
        val root = entries.first { it.type == 5 }
        val wb = entries.firstOrNull { it.type == 2 && (it.name.equals("Workbook", true) || it.name.equals("Book", true)) } ?: error("no Workbook stream")
        if (wb.size >= miniCutoff) return readChain(wb.start, wb.size)
        val miniStream = readChain(root.start, root.size)
        val miniFatBytes = readChain(miniFatStart, -1)
        val mf = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
        val miniFat = IntArray(miniFatBytes.size / 4) { mf.getInt(it * 4) }
        val out = java.io.ByteArrayOutputStream()
        var s = wb.start; var n = 0
        while (s >= 0 && s < miniFat.size && n++ < 1_000_000) { val o = s * miniSize; if (o < miniStream.size) out.write(miniStream, o, minOf(miniSize, miniStream.size - o)); s = miniFat[s] }
        return out.toByteArray().copyOf(wb.size.toInt())
    }

    // ------------------------------------------------------------------ BIFF8
    private class Rec(val type: Int, val data: ByteArray)

    private fun records(w: ByteArray, from: Int): Sequence<Pair<Int, Rec>> = sequence {
        var p = from
        while (p + 4 <= w.size) {
            val type = (w[p].toInt() and 0xFF) or ((w[p + 1].toInt() and 0xFF) shl 8)
            val len = (w[p + 2].toInt() and 0xFF) or ((w[p + 3].toInt() and 0xFF) shl 8)
            if (p + 4 + len > w.size) break
            yield(p to Rec(type, w.copyOfRange(p + 4, p + 4 + len)))
            p += 4 + len
        }
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(d: ByteArray, o: Int) = ByteBuffer.wrap(d, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun f64(d: ByteArray, o: Int) = ByteBuffer.wrap(d, o, 8).order(ByteOrder.LITTLE_ENDIAN).double

    /** A reader over SST + CONTINUE segments, aware that a string's characters may carry over into the next segment. */
    private class Segs(val segs: List<ByteArray>) {
        var si = 0; var p = 0
        fun ensure() { while (si < segs.size && p >= segs[si].size) { si++; p = 0 } }
        fun u8(): Int { ensure(); return segs[si][p++].toInt() and 0xFF }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)
        fun skip(n: Int) { repeat(n) { u8() } }
        fun chars(count: Int, wideStart: Boolean): String {
            val sb = StringBuilder(); var wide = wideStart; var left = count
            while (left > 0) {
                if (si < segs.size && p >= segs[si].size) { si++; p = 0; wide = (u8() and 1) == 1 }
                if (wide) sb.append(u16().toChar()) else sb.append(u8().toChar())
                left--
            }
            return sb.toString()
        }
    }

    private fun shortStr(d: ByteArray, o: Int, lenBytes: Int): String {
        val len = if (lenBytes == 1) d[o].toInt() and 0xFF else u16(d, o)
        val flags = d[o + lenBytes].toInt()
        val s = o + lenBytes + 1
        return if (flags and 1 == 1) String(d, s, minOf(len * 2, d.size - s), Charsets.UTF_16LE) else String(d, s, minOf(len, d.size - s), Charsets.ISO_8859_1)
    }

    private fun num(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    private fun rk(r: Int): Double { val v = if (r and 2 != 0) (r shr 2).toDouble() else java.lang.Double.longBitsToDouble((r.toLong() and 0xFFFFFFFCL) shl 32); return if (r and 1 != 0) v / 100 else v }

    private fun parseBiff(w: ByteArray): List<Sheet> {
        val sst = ArrayList<String>()
        val sheets = ArrayList<Pair<String, Int>>()
        var sstSegs: ArrayList<ByteArray>? = null
        for ((_, r) in records(w, 0)) {
            when (r.type) {
                0x00FC -> sstSegs = arrayListOf(r.data)
                0x003C -> sstSegs?.add(r.data)
                else -> {
                    sstSegs?.let { segs ->
                        val rd = Segs(segs); rd.u32(); val unique = rd.u32()
                        repeat(unique) {
                            runCatching {
                                val cc = rd.u16(); val flags = rd.u8()
                                val runs = if (flags and 8 != 0) rd.u16() else 0
                                val ext = if (flags and 4 != 0) rd.u32() else 0
                                sst += rd.chars(cc, flags and 1 == 1)
                                rd.skip(runs * 4 + ext)
                            }.onFailure { sst += "" }
                        }
                        sstSegs = null
                    }
                    if (r.type == 0x0085) sheets += shortStr(r.data, 6, 1) to i32(r.data, 0)
                    if (r.type == 0x000A) break
                }
            }
        }
        return sheets.map { (name, pos) ->
            val cells = HashMap<Long, String>(); var maxR = -1; var maxC = -1
            fun put(row: Int, col: Int, v: String) { cells[row.toLong() shl 16 or col.toLong()] = v; maxR = maxOf(maxR, row); maxC = maxOf(maxC, col) }
            var pendingFormula: Pair<Int, Int>? = null
            var first = true
            for ((_, r) in records(w, pos)) {
                val d = r.data
                when (r.type) {
                    0x0809 -> if (!first) break
                    0x000A -> break
                    0x00FD -> put(u16(d, 0), u16(d, 2), sst.getOrElse(i32(d, 6)) { "" })
                    0x0203 -> put(u16(d, 0), u16(d, 2), num(f64(d, 6)))
                    0x027E -> put(u16(d, 0), u16(d, 2), num(rk(i32(d, 6))))
                    0x00BD -> { val row = u16(d, 0); var col = u16(d, 2); var o = 4; while (o + 6 <= d.size - 2) { put(row, col++, num(rk(i32(d, o + 2)))); o += 6 } }
                    0x0204 -> put(u16(d, 0), u16(d, 2), shortStr(d, 6, 2))
                    0x0006 -> if (u16(d, 12) == 0xFFFF) { if (d[6].toInt() == 0) pendingFormula = u16(d, 0) to u16(d, 2) } else put(u16(d, 0), u16(d, 2), num(f64(d, 6)))
                    0x0207 -> pendingFormula?.let { (rr, cc) -> put(rr, cc, shortStr(d, 0, 2)); pendingFormula = null }
                }
                first = false
            }
            Sheet(name, (0..maxR).map { rr -> (0..maxC).map { cc -> cells[rr.toLong() shl 16 or cc.toLong()].orEmpty() } })
        }
    }
}
