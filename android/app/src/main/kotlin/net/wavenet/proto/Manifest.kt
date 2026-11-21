package net.wavenet.proto

/** Signed content manifest — byte-compatible with core/src/manifest.cpp. */
class Manifest(
    val contentId: ByteArray,
    val size: Long,
    val chunkSize: Int,
    val chunks: List<ByteArray>,
    val title: String, val description: String = "", val category: String = "", val mime: String = "",
    val license: String = "", val keywords: String = "",
    val isPrivate: Boolean = false,
    val allowRedistribution: Boolean = true,
    val fileName: String,
    val publisher: ByteArray,
    val createdMs: Long,
    val signature: ByteArray = ByteArray(64),
) {
    val chunkCount: Int get() = chunks.size
    val contentIdHex: String get() = Hex.encode(contentId)
    fun chunkLen(i: Int): Int {
        if (i < 0 || i >= chunkCount) throw IndexOutOfBoundsException("chunk index")
        return minOf(chunkSize.toLong(), size - i.toLong() * chunkSize).toInt()
    }

    fun signedBody(): ByteArray {
        val w = Writer().str(DOMAIN).raw(contentId).u64(size).u32(chunkSize.toLong()).u32(chunks.size.toLong())
        for (c in chunks) w.raw(c)
        return w.str(title).str(description).str(category).str(mime).str(license).str(keywords)
            .u8(if (isPrivate) 1 else 0).u8(if (allowRedistribution) 1 else 0).str(fileName).raw(publisher).u64(createdMs).bytes()
    }
    fun encode(): ByteArray = Writer().raw(signedBody()).raw(signature).bytes()

    fun signedBy(id: Identity): Manifest {
        val unsigned = copyWith(publisher = id.publicKey, signature = ByteArray(64))
        return unsigned.copyWith(signature = id.sign(unsigned.signedBody()))
    }

    private fun copyWith(publisher: ByteArray = this.publisher, signature: ByteArray = this.signature) =
        Manifest(contentId, size, chunkSize, chunks, title, description, category, mime, license, keywords, isPrivate,
                 allowRedistribution, fileName, publisher, createdMs, signature)

    companion object {
        const val DOMAIN = "WAVE manifest v1"
        const val MIN_CHUNK = 16 shl 10
        const val MAX_CHUNK = 1 shl 20
        const val DEFAULT_CHUNK = 256 shl 10
        const val MAX_CONTENT = 64L shl 30
        const val MAX_TEXT = 4096

        fun validate(m: Manifest) {
            if (m.chunkSize < MIN_CHUNK || m.chunkSize > MAX_CHUNK) throw DecodeError("chunk size out of range")
            if (m.size < 0 || m.size > MAX_CONTENT) throw DecodeError("content too large")
            val expect = if (m.size == 0L) 0L else (m.size + m.chunkSize - 1) / m.chunkSize
            if (m.chunks.size.toLong() != expect) throw DecodeError("chunk count mismatch")
            for (s in listOf(m.title, m.description, m.category, m.mime, m.license, m.keywords, m.fileName))
                if (s.toByteArray(Charsets.UTF_8).size > MAX_TEXT) throw DecodeError("text field too long")
            val n = m.fileName
            if ('/' in n || '\\' in n || n == ".." || n == "." || '\u0000' in n) throw DecodeError("unsafe file name")
        }

        fun decode(b: ByteArray): Manifest {
            val r = Reader(b, maxBlob = MAX_TEXT)
            if (r.str() != DOMAIN) throw DecodeError("not a manifest")
            val cid = r.raw(32); val size = r.u64(); val cs = r.u32(); val n = r.u32()
            if (n * 32 > r.remaining) throw DecodeError("chunk list truncated")
            val chunks = List(n.toInt()) { r.raw(32) }
            val title = r.str(); val desc = r.str(); val cat = r.str(); val mime = r.str(); val lic = r.str(); val kw = r.str()
            val vis = r.u8(); val redist = r.u8()
            if (vis > 1) throw DecodeError("bad visibility")
            if (redist > 1) throw DecodeError("bad flag")
            val fileName = r.str(); val pub = r.raw(32); val created = r.u64(); val sig = r.raw(64)
            r.expectDone()
            if (cs > MAX_CHUNK) throw DecodeError("chunk size out of range")
            val m = Manifest(cid, size, cs.toInt(), chunks, title, desc, cat, mime, lic, kw, vis == 1, redist == 1, fileName, pub, created, sig)
            validate(m)
            if (!Crypto.verify(pub, m.signedBody(), sig)) throw DecodeError("bad manifest signature")
            return m
        }
    }
}
