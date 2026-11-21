package net.wavenet.proto

import java.io.ByteArrayOutputStream

/** Thrown for any malformed / oversized / wrongly signed input. Mirrors C++ wavenet::DecodeError. */
class DecodeError(msg: String) : Exception(msg)

/** Little-endian writer; byte-compatible with core/src/bytes.cpp. */
class Writer {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { for (i in 0 until 2) out.write((v ushr (8 * i)) and 0xff) }
    fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun raw(b: ByteArray) = apply { out.write(b, 0, b.size) }
    fun blob(b: ByteArray) = apply { u32(b.size.toLong()); raw(b) }
    fun str(s: String) = blob(s.toByteArray(Charsets.UTF_8))
    fun bytes(): ByteArray = out.toByteArray()
    val size: Int get() = out.size()
}

/** Bounds-checked little-endian reader; byte-compatible with core/src/bytes.cpp. */
class Reader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size, private val maxBlob: Int = 16 shl 20) {
    val remaining: Int get() = end - pos
    private fun need(n: Int) { if (n < 0 || n > remaining) throw DecodeError("truncated input") }
    fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }
    fun u16(): Int { need(2); var v = 0; for (i in 0 until 2) v = v or ((b[pos + i].toInt() and 0xff) shl (8 * i)); pos += 2; return v }
    fun u32(): Long { need(4); var v = 0L; for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 4; return v }
    fun u64(): Long { need(8); var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 8; return v }
    fun raw(n: Int): ByteArray { need(n); val r = b.copyOfRange(pos, pos + n); pos += n; return r }
    fun blob(): ByteArray { val n = u32(); if (n > maxBlob) throw DecodeError("blob exceeds limit"); return raw(n.toInt()) }
    fun str(): String = String(blob(), Charsets.UTF_8)
    fun expectDone() { if (remaining != 0) throw DecodeError("trailing bytes") }
}

object Hex {
    private const val D = "0123456789abcdef"
    fun encode(b: ByteArray): String { val sb = StringBuilder(b.size * 2); for (x in b) { sb.append(D[(x.toInt() shr 4) and 15]); sb.append(D[x.toInt() and 15]) }; return sb.toString() }
    fun decode(s: String): ByteArray {
        if (s.length % 2 != 0) throw DecodeError("odd hex length")
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[2 * i], 16); val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) throw DecodeError("invalid hex digit")
            ((hi shl 4) or lo).toByte()
        }
    }
}
