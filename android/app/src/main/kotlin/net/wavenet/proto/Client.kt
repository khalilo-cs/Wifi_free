package net.wavenet.proto

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean


class NodeContext(val id: Identity, val store: ContentStore, val networkId: Long = 1, val trust: TrustPolicy = TrustPolicy())

/** One WAVE-NP session to a peer (TLS 1.3 + channel binding). One request at a time, or pipelined chunk requests. */
class PeerClient(val route: PeerRoute, ctx: NodeContext, private val timeoutMs: Int = 15_000) : AutoCloseable {
    private val s: Session = Session.initiate(route.open(timeoutMs), ctx.id, ctx.networkId, ctx.trust)
    private var nextReq = 1L
    val peerIdHex get() = s.peerIdHex
    val describe get() = s.link.describe

    private fun call(type: Int, payload: ByteArray, expect: Int): Frame {
        val req = nextReq++
        s.send(type, payload, req)
        return check(s.recv(timeoutMs), req, expect)
    }
    private fun check(f: Frame, req: Long, expect: Int): Frame {
        if (f.requestId != req) throw SessionError("response id mismatch")
        if (f.type == MsgType.ERROR) throw SessionError("peer error: " + String(f.payload, Charsets.UTF_8))
        if (f.type != expect) throw SessionError("unexpected response type")
        return f
    }

    fun search(q: Wire.Query): List<Manifest> = Wire.parseSearchResponse(call(MsgType.SEARCH_REQUEST, Wire.searchRequest(q), MsgType.SEARCH_RESPONSE).payload)

    fun manifest(cid: ByteArray): Manifest? {
        val r = Reader(call(MsgType.MANIFEST_REQUEST, cid, MsgType.MANIFEST_RESPONSE).payload, maxBlob = Frame.MAX_PAYLOAD)
        if (r.u8() == 0) return null
        val m = Manifest.decode(r.blob()); r.expectDone()
        if (!m.contentId.contentEquals(cid)) throw SessionError("peer returned manifest for different content")
        return m
    }

    fun requestChunk(cid: ByteArray, idx: Int): Long { val req = nextReq++; s.send(MsgType.CHUNK_REQUEST, Wire.chunkRequest(cid, idx), req); return req }

    fun receiveChunk(cid: ByteArray, idx: Int, req: Long): ByteArray {
        val r = Reader(check(s.recv(timeoutMs), req, MsgType.DATA_CHUNK).payload, maxBlob = Frame.MAX_PAYLOAD)
        val c = r.raw(32); val i = r.u32().toInt(); val d = r.blob(); r.expectDone()
        if (!c.contentEquals(cid) || i != idx) throw SessionError("chunk response mismatch")
        return d
    }

    override fun close() = s.close()
}

class DownloadStats {
    var complete = false; var totalChunks = 0; var alreadyHad = 0; var fetched = 0; var rejected = 0
    var bytesFetched = 0L; var seconds = 0.0; var firstChunkSeconds = -1.0; var error = ""
    val perPeer = LinkedHashMap<String, Int>()
    val perMedium = LinkedHashMap<String, Int>()   // chunks delivered per medium (tcp, wifi-direct, bluetooth...)
    val manifest: Manifest? get() = m
    internal var m: Manifest? = null
}

/**
 * Multi-source, pipelined, resumable download (same algorithm as core/src/node.cpp::download):
 * parallel connect, manifest content ID must match, per-chunk SHA-256 before write, a peer that sends a bad chunk is dropped,
 * in-flight work of a failed peer is re-queued, whole-file SHA-256 at the end. `cancel` keeps the download resumable.
 */
object Downloader {
    fun download(ctx: NodeContext, cid: ByteArray, peers: List<PeerRoute>, window: Int = 4, cancel: AtomicBoolean = AtomicBoolean(false),
                 progress: (done: Int, total: Int, bytes: Long) -> Unit = { _, _, _ -> }): DownloadStats {
        val st = DownloadStats()
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1e9
        val win = window.coerceIn(1, 64)

        val local = ctx.store.get(cid)
        var man: Manifest? = if (local != null && local.state != LocalState.REMOTE && ctx.store.hasComplete(cid)) local.manifest else null
        // connect in parallel
        val slots = arrayOfNulls<PeerClient>(peers.size)
        val errs = arrayOfNulls<String>(peers.size)
        val ts = peers.indices.map { i -> Thread { try { slots[i] = PeerClient(peers[i], ctx) } catch (e: Exception) { errs[i] = "peer ${peers[i].key}: ${e.message}" } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        val clients = ArrayList<PeerClient>()
        for (i in peers.indices) {
            val c = slots[i]
            if (c == null) { st.error = errs[i] ?: "connect failed"; continue }
            if (man == null) {
                try { man = c.manifest(cid) } catch (e: Exception) { st.error = "peer ${peers[i].key}: ${e.message}"; c.close(); continue }
            }
            clients.add(c)
        }
        val m = man
        if (m == null) {
            clients.forEach { it.close() }
            if (st.error.isEmpty() || st.error.contains("connect")) st.error = if (st.error.isEmpty()) "content not found on any peer" else st.error
            st.seconds = elapsed(); return st
        }
        st.m = m
        ctx.store.put(m, LocalState.REMOTE)
        st.totalChunks = m.chunkCount
        val have = ctx.store.openPartial(m)
        val todo = ArrayDeque<Int>()
        for (i in have.indices) if (have[i]) st.alreadyHad++ else todo.add(i)
        val lock = Any()

        val workers = clients.map { c -> Thread {
            val inflight = ArrayDeque<Pair<Int, Long>>()
            fun requeueAll() = synchronized(lock) { for (p in inflight) todo.add(p.first); inflight.clear() }
            try {
                while (true) {
                    while (inflight.size < win) {
                        val idx = synchronized(lock) { if (cancel.get() || todo.isEmpty()) null else todo.poll() } ?: break
                        try { inflight.add(idx to c.requestChunk(m.contentId, idx)) }
                        catch (e: Exception) { synchronized(lock) { todo.add(idx) }; requeueAll(); return@Thread }
                    }
                    if (inflight.isEmpty()) return@Thread
                    val (idx, req) = inflight.first
                    val data = try { c.receiveChunk(m.contentId, idx, req) } catch (e: Exception) { requeueAll(); return@Thread }
                    inflight.poll()
                    if (!ctx.store.writeChunk(m, idx, data)) {
                        synchronized(lock) { st.rejected++; todo.add(idx) }
                        requeueAll(); return@Thread   // drop a peer after one bad chunk
                    }
                    synchronized(lock) {
                        st.fetched++; st.bytesFetched += data.size
                        st.perPeer[c.describe] = (st.perPeer[c.describe] ?: 0) + 1
                        st.perMedium[c.route.medium] = (st.perMedium[c.route.medium] ?: 0) + 1
                        if (st.firstChunkSeconds < 0) st.firstChunkSeconds = elapsed()
                        progress(st.fetched + st.alreadyHad, st.totalChunks, st.bytesFetched)
                    }
                }
            } finally { c.close() }
        } }
        workers.forEach { it.start() }; workers.forEach { it.join() }
        st.seconds = elapsed()
        if (todo.isNotEmpty()) { st.error = if (cancel.get()) "stopped" else "no remaining peer could supply all chunks"; return st }
        if (!ctx.store.finalize(m)) { st.error = "final SHA-256 mismatch"; return st }
        ctx.store.put(m, LocalState.DOWNLOADED)
        st.complete = true; st.error = ""
        return st
    }
}
