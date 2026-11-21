package net.wavenet.proto

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Signed NODE_ANNOUNCE over UDP — byte-compatible with core/src/discovery.cpp. */
class Announce(val tcpPort: Int, val name: String, val roles: Long = 3, val contentCount: Long = 0) {
    fun encode(): ByteArray {
        var n = name; while (n.toByteArray(Charsets.UTF_8).size > 64) n = n.dropLast(1)
        return Writer().u16(tcpPort).u32(roles).u32(contentCount).str(n).bytes()
    }
    companion object {
        fun decode(b: ByteArray): Announce {
            val r = Reader(b, maxBlob = 64)
            val a = Announce(r.u16(), "", r.u32(), r.u32()).let { Announce(it.tcpPort, r.str(), it.roles, it.contentCount) }
            r.expectDone()
            if (a.tcpPort == 0) throw DecodeError("announce without port")
            return a
        }
    }
}

class PeerInfo(val nodeIdHex: String, val ip: String, val ann: Announce, val lastTs: Long, val lastSeenMs: Long) {
    val addr get() = PeerAddr(ip, ann.tcpPort)
}

/**
 * LAN discovery. On Android the caller must hold a WifiManager.MulticastLock while this runs.
 * Rules (tested on the C++ side): valid signature, same network, ±60 s timestamp, strictly newer than last accepted,
 * not self, bounded table, 30 s expiry.
 */
class Discovery(private val me: Identity, private val net: Long = 1, val port: Int = 47800, private val group: String = "239.255.77.86",
                iface: NetworkInterface? = null, private val maxPeers: Int = 256) : AutoCloseable {
    private val sock = MulticastSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        bind(InetSocketAddress(this@Discovery.port))   // NOTE: plain `port` here would be MulticastSocket.getPort() (= -1)
        timeToLive = 1
        if (iface != null) networkInterface = iface
        try { joinGroup(InetSocketAddress(InetAddress.getByName(group), this@Discovery.port), iface ?: networkInterface) } catch (e: Exception) { /* multicast unavailable: unicast seeds still work */ }
    }
    val boundPort: Int get() = sock.localPort
    private val peers = LinkedHashMap<String, PeerInfo>()

    fun announce(a: Announce, seeds: List<PeerAddr> = emptyList(), multicast: Boolean = true) {
        val ts = System.currentTimeMillis()
        val wire = Frame.encode(Frame(MsgType.NODE_ANNOUNCE, net, me.publicKey, a.encode(), seq = ts, timestampMs = ts, hopLimit = 1), me)
        val targets = (if (multicast) listOf(PeerAddr(group, port)) else emptyList()) + seeds
        for (t in targets) try { sock.send(DatagramPacket(wire, wire.size, InetSocketAddress(t.host, t.port))) } catch (e: Exception) { }
    }

    @Synchronized fun handle(d: ByteArray, fromIp: String, now: Long = System.currentTimeMillis()): Boolean {
        if (d.size > 2048) return false
        val f = try { Frame.decode(d) } catch (e: DecodeError) { return false }
        val a = try { Announce.decode(f.payload) } catch (e: DecodeError) { return false }
        if (f.type != MsgType.NODE_ANNOUNCE || f.networkId != net || f.src.contentEquals(me.publicKey)) return false
        if (Math.abs(now - f.timestampMs) > 60_000) return false
        val id = Hex.encode(f.srcId)
        val old = peers[id]
        if (old != null && f.timestampMs <= old.lastTs) return false
        if (old == null && peers.size >= maxPeers) peers.remove(peers.values.minByOrNull { it.lastSeenMs }!!.nodeIdHex)
        peers[id] = PeerInfo(id, fromIp, a, f.timestampMs, now)
        return true
    }

    /** Receives datagrams for up to `timeoutMs`. Returns number accepted. */
    fun poll(timeoutMs: Int): Int {
        var n = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(4096)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            sock.soTimeout = left.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try { sock.receive(p) } catch (e: SocketTimeoutException) { break } catch (e: Exception) { break }
            if (handle(buf.copyOf(p.length), p.address.hostAddress ?: "")) n++
        }
        return n
    }

    @Synchronized fun peers(now: Long = System.currentTimeMillis()): List<PeerInfo> = peers.values.filter { now - it.lastSeenMs <= 30_000 }

    override fun close() = sock.close()
}
