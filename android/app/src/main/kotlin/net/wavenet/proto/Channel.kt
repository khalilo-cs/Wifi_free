package net.wavenet.proto

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Any reliable, ordered byte stream: a TCP socket, a Bluetooth RFCOMM socket, an in-memory pipe (tests)...
 * WAVE-NP + TLS run unchanged on top of it (transport independence, W1 Radio Fabric).
 * @param setReadTimeout native read timeout if the medium has one (TCP); null => a watchdog closes the channel instead
 */
class ByteChannel(val input: InputStream, val output: OutputStream, val name: String, val remoteKey: String,
                  private val setReadTimeout: ((Int) -> Unit)?, private val closer: () -> Unit, val medium: String) {
    @Volatile var closed = false; private set

    fun close() { if (!closed) { closed = true; try { closer() } catch (e: Exception) { } } }

    /** Runs a blocking read with a deadline. Media without native timeouts are closed by a watchdog if it expires. */
    fun <T> withTimeout(timeoutMs: Int, read: () -> T): T {
        val tm = setReadTimeout
        if (tm != null) { tm(timeoutMs); return read() }
        var fired = false
        val f: ScheduledFuture<*> = watchdog.schedule({ fired = true; close() }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try { return read() } catch (e: Exception) { if (fired) throw TransportError("recv timeout"); throw e } finally { f.cancel(false) }
    }

    companion object {
        private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wave-watchdog").apply { isDaemon = true } }
        fun of(s: Socket, name: String): ByteChannel = ByteChannel(s.getInputStream(), s.getOutputStream(), name,
            "ip:" + (s.inetAddress?.hostAddress ?: "?"), { ms -> s.soTimeout = ms }, { s.close() }, "tcp")
    }
}

private fun readExact(inp: InputStream, buf: ByteArray, off: Int, n: Int) {
    var got = 0
    while (got < n) {
        val r = try { inp.read(buf, off + got, n - got) } catch (e: java.net.SocketTimeoutException) { throw TransportError("recv timeout") }
            catch (e: TransportError) { throw e } catch (e: Exception) { throw TransportError("recv failed: ${e.message}") }
        if (r < 0) throw TransportError("connection closed by peer")
        got += r
    }
}

/** Frame reader/writer over a channel (optionally through TLS streams). Same size checks as the C++ links. */
class StreamLink(private val ch: ByteChannel, private val inp: InputStream, private val out: OutputStream,
                 override val describe: String, override val channelBinding: ByteArray = ByteArray(0)) : Link {
    override val encrypted: Boolean get() = channelBinding.isNotEmpty()
    val medium: String get() = ch.medium
    private val sendLock = Any()
    override fun sendFrame(frame: ByteArray) = synchronized(sendLock) {
        try { out.write(frame); out.flush() } catch (e: Exception) { throw TransportError("send failed: ${e.message}") }
    }
    override fun recvFrame(timeoutMs: Int): ByteArray = ch.withTimeout(timeoutMs) {
        val head = ByteArray(Frame.BASE_HEADER_LEN)
        readExact(inp, head, 0, head.size)
        val total = try { Frame.peekSize(head) } catch (e: DecodeError) { throw TransportError("bad frame: ${e.message}") }
        if (total < head.size || total > Frame.MAX_FRAME) throw TransportError("bad frame size")
        val buf = head.copyOf(total)
        readExact(inp, buf, head.size, total - head.size)
        buf
    }
    override fun close() = ch.close()
}

/** A way to reach a peer over some medium. The downloader can mix routes of different media in one download. */
interface PeerRoute {
    val key: String                      // unique per peer+medium, e.g. "tcp:10.0.0.2:47801", "bt:AA:BB:.."
    val medium: String
    fun open(timeoutMs: Int): Link       // returns an encrypted (TLS 1.3) link, ready for Session.initiate
}

/** TCP/IP route: Wi-Fi LAN, hotspot, Wi-Fi Direct group (it is IP once the group is formed), Linux node. */
class PeerAddr(val host: String, val port: Int, override val medium: String = "tcp") : PeerRoute {
    override val key get() = "tcp:$host:$port"
    override fun open(timeoutMs: Int): Link = Tls.connect(host, port, timeoutMs, medium)
    override fun toString() = "$host:$port"
}
