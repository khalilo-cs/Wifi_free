package net.wavenet.proto

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Per-key token bucket (mirror of core RateLimiter), bounded memory. */
class RateLimiter(private val burst: Double, private val perSec: Double, private val maxKeys: Int = 4096) {
    private class B(var tokens: Double, var last: Long)
    private val m = LinkedHashMap<String, B>()
    @Synchronized fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        var b = m[key]
        if (b == null) {
            if (m.size >= maxKeys) m.remove(m.entries.minByOrNull { it.value.last }!!.key)
            b = B(burst, now); m[key] = b
        }
        if (now > b.last) b.tokens = minOf(burst, b.tokens + (now - b.last) / 1000.0 * perSec)
        b.last = maxOf(b.last, now)
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0; return true
    }
}

/** Serves public content to authenticated peers over TLS 1.3 (same rules and limits as the C++ NodeServer). */
class NodeServer(private val ctx: NodeContext, bindHost: String = "0.0.0.0", port: Int = 0,
                 private val maxConns: Int = 16, private val maxPerIp: Int = 4) : AutoCloseable {
    private val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 32) }
    val port: Int get() = ss.localPort
    private val running = AtomicBoolean(true)
    private val active = AtomicInteger(0)
    private val perIp = HashMap<String, Int>()
    private val limiter = RateLimiter(20.0, 5.0)
    val chunksServed = AtomicLong(0)
    val refused = AtomicLong(0)

    fun servable(e: Entry): Boolean {
        val m = e.manifest
        if (m.isPrivate || e.state == LocalState.REMOTE) return false
        if (!m.publisher.contentEquals(ctx.id.publicKey) && !m.allowRedistribution) return false
        return ctx.store.hasComplete(m.contentId)
    }

    private val channels = java.util.Collections.synchronizedSet(HashSet<ByteChannel>())

    /**
     * Serves ONE connection from any medium (TCP accept loop below, Android Bluetooth RFCOMM server, tests).
     * Blocks until the connection ends. Rate limit + caps are keyed by the remote address (IP or Bluetooth address).
     */
    fun serveChannel(ch: ByteChannel) {
        val key = ch.remoteKey
        val ok = running.get() && limiter.allow(key) && synchronized(perIp) {
            if (active.get() >= maxConns || (perIp[key] ?: 0) >= maxPerIp) false else { perIp[key] = (perIp[key] ?: 0) + 1; active.incrementAndGet(); true }
        }
        if (!ok) { refused.incrementAndGet(); ch.close(); return }
        channels.add(ch)
        try { serveLink(Tls.serverWrap(ch)) } catch (e: Exception) { } finally {
            ch.close(); channels.remove(ch)
            synchronized(perIp) { val n = (perIp[key] ?: 1) - 1; if (n <= 0) perIp.remove(key) else perIp[key] = n; active.decrementAndGet() }
        }
    }

    private val acceptThread = Thread {
        while (running.get()) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            try { s.tcpNoDelay = true } catch (e: Exception) { }
            val ch = ByteChannel.of(s, "tls+tcp:" + s.inetAddress.hostAddress + ":" + s.port)
            Thread { serveChannel(ch) }.apply { isDaemon = true; name = "wave-conn" }.start()
        }
    }.apply { isDaemon = true; name = "wave-accept"; start() }

    private fun serveLink(link: Link) {
        val s = Session.respond(link, ctx.id, ctx.networkId, ctx.trust)
        val reqLimit = RateLimiter(1000.0, 500.0, 1)
        while (running.get()) {
            val f = try { s.recv(60_000) } catch (e: Exception) { return }
            fun err(msg: String) = s.send(MsgType.ERROR, msg.toByteArray(), f.requestId)
            if (!reqLimit.allow("s")) { try { err("rate limited") } catch (e: Exception) { }; return }
            try {
                when (f.type) {
                    MsgType.SEARCH_REQUEST -> {
                        val q = Wire.parseSearchRequest(f.payload)
                        s.send(MsgType.SEARCH_RESPONSE, Wire.searchResponse(ctx.store.search(q, publicOnly = true).filter { servable(it) }.map { it.manifest }), f.requestId)
                    }
                    MsgType.MANIFEST_REQUEST -> {
                        val r = Reader(f.payload); val cid = r.raw(32); r.expectDone()
                        val e = ctx.store.get(cid)
                        val w = Writer()
                        if (e != null && servable(e)) w.u8(1).blob(e.manifest.encode()) else w.u8(0)
                        s.send(MsgType.MANIFEST_RESPONSE, w.bytes(), f.requestId)
                    }
                    MsgType.CHUNK_REQUEST -> {
                        val r = Reader(f.payload); val cid = r.raw(32); val idx = r.u32(); r.expectDone()
                        val e = ctx.store.get(cid)
                        if (e == null || !servable(e) || idx >= e.manifest.chunkCount) { err("not available") }
                        else { s.send(MsgType.DATA_CHUNK, Wire.dataChunk(cid, idx.toInt(), ctx.store.readChunk(e.manifest, idx.toInt())), f.requestId); chunksServed.incrementAndGet() }
                    }
                    else -> err("unsupported message")
                }
            } catch (e: DecodeError) { try { err("malformed request") } catch (x: Exception) { return } }
            catch (e: TransportError) { return }
        }
    }

    override fun close() {
        running.set(false)
        try { ss.close() } catch (e: Exception) { }
        synchronized(channels) { for (ch in channels.toList()) ch.close() }
        acceptThread.join(2000)
    }
}
