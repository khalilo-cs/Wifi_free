package net.wavenet.proto

/** Application payloads — byte-compatible with `wire::` in core/src/node.cpp. */
object Wire {
    class Query(val text: String = "", val category: String = "", val limit: Int = 50, val offset: Int = 0)

    private fun clip(s: String, maxBytes: Int): String {
        var t = s
        while (t.toByteArray(Charsets.UTF_8).size > maxBytes) t = t.dropLast(1)
        return t
    }
    fun searchRequest(q: Query): ByteArray =
        Writer().str(clip(q.text, 256)).str(clip(q.category, 64)).u32(q.limit.toLong()).u32(q.offset.toLong()).bytes()
    fun parseSearchRequest(b: ByteArray): Query {
        val r = Reader(b, maxBlob = 256)
        val q = Query(r.str(), r.str(), minOf(r.u32(), 50L).toInt(), minOf(r.u32(), Int.MAX_VALUE.toLong()).toInt())
        r.expectDone(); return q
    }
    fun searchResponse(ms: List<Manifest>): ByteArray {
        val body = Writer(); var n = 0
        for (m in ms) {
            val e = m.encode()
            if (body.size + e.size + 8 > Frame.MAX_PAYLOAD - 1024) break
            body.blob(e); n++
        }
        return Writer().u32(n.toLong()).raw(body.bytes()).bytes()
    }
    fun parseSearchResponse(b: ByteArray): List<Manifest> {
        val r = Reader(b, maxBlob = Frame.MAX_PAYLOAD)
        val n = r.u32()
        if (n > 200) throw DecodeError("too many results")
        val out = List(n.toInt()) { Manifest.decode(r.blob()) }
        r.expectDone(); return out
    }
    fun chunkRequest(cid: ByteArray, idx: Int) = Writer().raw(cid).u32(idx.toLong()).bytes()
    fun dataChunk(cid: ByteArray, idx: Int, data: ByteArray) = Writer().raw(cid).u32(idx.toLong()).blob(data).bytes()
}
