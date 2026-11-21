package net.wavenet.proto

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory byte pipe; optional throttle (bytes/s) to emulate a slow medium. No IP, no native read timeout. */
class MemPipe(private val bytesPerSec: Long = 0) {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private val EOF = ByteArray(0)
    val out = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw java.io.IOException("pipe closed")
            if (bytesPerSec > 0) Thread.sleep(len * 1000L / bytesPerSec)
            q.put(b.copyOfRange(off, off + len))
        }
        override fun close() { closed = true; q.put(EOF) }
    }
    val inp = object : InputStream() {
        private var cur = ByteArray(0); private var pos = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (pos >= cur.size) {
                if (closed && q.isEmpty()) return -1
                val next = q.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (next === EOF) { closed = true; return -1 }
                cur = next; pos = 0
            }
            val n = minOf(len, cur.size - pos); System.arraycopy(cur, pos, b, off, n); pos += n; return n
        }
    }
    fun close() { if (!closed) { closed = true; q.offer(EOF) } }
}

/** A route over an in-memory duplex pipe, served by `server.serveChannel` — stands in for a Bluetooth RFCOMM stream. */
class PipeRoute(private val server: NodeServer, private val name: String, private val bytesPerSec: Long = 0) : PeerRoute {
    override val key get() = "pipe:$name"
    override val medium get() = "pipe-$name"
    override fun open(timeoutMs: Int): Link {
        val c2s = MemPipe(bytesPerSec); val s2c = MemPipe(bytesPerSec)
        val serverSide = ByteChannel(c2s.inp, s2c.out, "srv-$name", "pipe:$name-client", null, { c2s.close(); s2c.close() }, "pipe")
        val clientSide = ByteChannel(s2c.inp, c2s.out, "tls+pipe:$name", "pipe:$name", null, { c2s.close(); s2c.close() }, "pipe")
        Thread { server.serveChannel(serverSide) }.apply { isDaemon = true }.start()
        return Tls.clientWrap(clientSide, timeoutMs)
    }
}

class TransportTest {
    private fun tmp(): File = Files.createTempDirectory("wave-transport").toFile()
    private fun publisher(d: File, size: Int): Pair<NodeContext, Manifest> {
        val ctx = NodeContext(Identity.generate(), ContentStore(File(d, "pub")))
        val data = ByteArray(size).also { java.util.Random(7).nextBytes(it) }
        return ctx to data.inputStream().use { ctx.store.import(it, "f.bin", ctx.id, chunkSize = Manifest.MIN_CHUNK) }
    }

    @Test fun fullDownloadOverNonIpStream() {
        val d = tmp(); val (pub, m) = publisher(d, 400_000)
        NodeServer(pub, "127.0.0.1", 0).use { srv ->
            val me = NodeContext(Identity.generate(), ContentStore(File(d, "me")))
            val st = Downloader.download(me, m.contentId, listOf(PipeRoute(srv, "bt")))
            assertTrue(st.complete, st.error)
            assertEquals(mapOf("pipe-bt" to m.chunkCount), st.perMedium.toMap())
            assertTrue(me.store.blob(m.contentId).readBytes().contentEquals(pub.store.blob(m.contentId).readBytes()))
        }
    }

    @Test fun hybridDownloadMixesMedia() {
        val d = tmp(); val (pub, m) = publisher(d, 800_000)
        NodeServer(pub, "127.0.0.1", 0).use { srv ->
            val me = NodeContext(Identity.generate(), ContentStore(File(d, "me")))
            val st = Downloader.download(me, m.contentId, listOf(PeerAddr("127.0.0.1", srv.port), PipeRoute(srv, "bt")), window = 2)
            assertTrue(st.complete, st.error)
            assertEquals(setOf("tcp", "pipe-bt"), st.perMedium.keys, "both media should deliver: ${st.perMedium}")
            assertEquals(m.chunkCount, st.perMedium.values.sum())
        }
    }

    @Test fun slowMediumGetsFewerChunks() {
        val d = tmp(); val (pub, m) = publisher(d, 1_000_000)   // 62 chunks of 16 KiB
        NodeServer(pub, "127.0.0.1", 0).use { srv ->
            val me = NodeContext(Identity.generate(), ContentStore(File(d, "me")))
            val st = Downloader.download(me, m.contentId, listOf(PeerAddr("127.0.0.1", srv.port), PipeRoute(srv, "slow", bytesPerSec = 100_000)), window = 2)
            assertTrue(st.complete, st.error)
            val fast = st.perMedium["tcp"] ?: 0; val slow = st.perMedium["pipe-slow"] ?: 0
            println("emulated media split: tcp=$fast slow(100 kB/s)=$slow in ${"%.2f".format(st.seconds)} s")
            assertTrue(fast > slow, "faster medium should carry more chunks (tcp=$fast slow=$slow)")
        }
    }

    @Test fun watchdogClosesSilentChannel() {
        val p1 = MemPipe(); val p2 = MemPipe()
        val ch = ByteChannel(p1.inp, p2.out, "silent", "x", null, { p1.close(); p2.close() }, "pipe")
        val link = StreamLink(ch, ch.input, ch.output, "silent")
        val t0 = System.nanoTime()
        val err = try { link.recvFrame(300); "no error" } catch (e: TransportError) { e.message ?: "" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("timeout" in err || "closed" in err, err)
        assertTrue(ms in 250..3000, "took $ms ms")
    }
}
