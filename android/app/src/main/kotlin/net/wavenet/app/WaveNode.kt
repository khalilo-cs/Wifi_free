package net.wavenet.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import net.wavenet.core.BlePayload
import net.wavenet.core.Decision
import net.wavenet.core.MediumRank
import net.wavenet.core.PolicyEngine
import net.wavenet.core.Role
import net.wavenet.proto.Announce
import net.wavenet.proto.ContentStore
import net.wavenet.proto.Discovery
import net.wavenet.proto.Downloader
import net.wavenet.proto.Hex
import net.wavenet.proto.Manifest
import net.wavenet.proto.NodeContext
import net.wavenet.proto.NodeServer
import net.wavenet.proto.PeerAddr
import net.wavenet.proto.PeerClient
import net.wavenet.proto.PeerRoute
import net.wavenet.proto.Wire

class DownloadJob(val manifest: Manifest, val peers: List<PeerRoute>) {
    @Volatile var done = 0
    @Volatile var total = manifest.chunkCount
    @Volatile var bytes = 0L
    @Volatile var state = State.RUNNING
    @Volatile var error = ""
    @Volatile var seconds = 0.0
    @Volatile var perMedium: Map<String, Int> = emptyMap()
    val cancel = AtomicBoolean(false)
    val startedNs = System.nanoTime()
    enum class State { RUNNING, DONE, FAILED }
}

class SearchHit(val manifest: Manifest, val routes: MutableList<PeerRoute>)

/**
 * Process-wide WAVE node (hybrid hub). Runs ONLY while the app is in the foreground (no hidden background work).
 * Media: Wi-Fi LAN/hotspot (TCP), Wi-Fi Direct (TCP inside the P2P group), Bluetooth Classic (RFCOMM), BLE (discovery hint).
 * Serving/advertising on any medium happens only while the user's policy allows sharing.
 */
object WaveNode {
    const val TCP_PORT = 47801
    lateinit var ctx: NodeContext; private set
    private lateinit var app: Context
    @Volatile var server: NodeServer? = null; private set
    @Volatile var lastDecision: Decision = Decision.DISABLED; private set
    @Volatile var radiosOn = false; private set
    private var discovery: Discovery? = null
    private var loop: Thread? = null
    private val running = AtomicBoolean(false)
    private var mlock: WifiManager.MulticastLock? = null
    var bt: BtTransport? = null; private set
    var ble: BleBeacon? = null; private set
    var wd: WifiDirectTransport? = null; private set
    val manualPeers = CopyOnWriteArrayList<PeerAddr>()
    private val wdRoutes = ConcurrentHashMap<String, PeerRoute>()
    val jobs = CopyOnWriteArrayList<DownloadJob>()

    @Synchronized fun init(c: Context) {
        if (::ctx.isInitialized) return
        app = c.applicationContext
        ctx = NodeContext(KeyVault.loadOrCreate(app), ContentStore(File(app.filesDir, "wave")))
        bt = BtTransport(app); ble = BleBeacon(app)
        wd = WifiDirectTransport(app) { route -> addWifiDirectRoute(route, introduce = true) }
    }

    private fun deviceName() = Build.MODEL ?: "android"

    @Synchronized fun onForeground() {
        if (running.getAndSet(true)) return
        mlock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("wavenet")?.apply { setReferenceCounted(false); acquire() }
        discovery = try { Discovery(ctx.id) } catch (e: Exception) { try { Discovery(ctx.id, port = 0) } catch (x: Exception) { null } }
        if (Prefs(app).radios) startRadios()
        loop = Thread {
            var lastAnnounce = 0L; var lastPolicy = 0L; var lastRadioScan = 0L
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - lastPolicy > 3000) { applyPolicy(); syncRadios(); lastPolicy = now }
                if (radiosOn && now - lastRadioScan > 60_000) { bt?.scan(); wd?.rediscover(); lastRadioScan = now }
                val srv = server
                if (srv != null && now - lastAnnounce > 2000) {
                    discovery?.announce(Announce(srv.port, deviceName(), roles = 3, contentCount = ctx.store.all().size.toLong()))
                    lastAnnounce = now
                }
                val d = discovery
                if (d != null) d.poll(500) else Thread.sleep(500)
            }
        }.apply { isDaemon = true; name = "wave-node"; start() }
    }

    @Synchronized fun onBackground() {
        if (!running.getAndSet(false)) return
        loop?.join(2000); loop = null
        discovery?.close(); discovery = null
        stopRadios(keepPreference = true)
        stopServer()
        for (j in jobs) j.cancel.set(true)   // downloads stop; they resume from verified chunks next time
        try { mlock?.release() } catch (e: Exception) { }
        mlock = null
    }

    /** Starts/stops serving according to the user's limits (battery, metered data, sharing switch). */
    @Synchronized fun applyPolicy() {
        val d = PolicyEngine.evaluate(Prefs(app).policy, DeviceProbe.state(app), Role.HOST)
        lastDecision = d
        if (d == Decision.ALLOWED && running.get() && server == null) {
            server = (try { NodeServer(ctx, "0.0.0.0", TCP_PORT) } catch (e: Exception) { try { NodeServer(ctx, "0.0.0.0", 0) } catch (x: Exception) { null } })
                ?.also { s -> s.onPeer = { _, key -> learnReverseRoute(key) } }
        } else if (d != Decision.ALLOWED) stopServer()
    }

    private fun stopServer() { server?.close(); server = null }

    // ---------- radios ----------
    /** Called after the user granted the radio permissions. */
    @Synchronized fun startRadios() {
        radiosOn = true
        Prefs(app).radios = true
        wd?.start(ctx.id.nodeIdHex)
        bt?.scan()
        syncRadios()
    }

    @Synchronized fun stopRadios(keepPreference: Boolean = false) {
        radiosOn = false
        if (!keepPreference) Prefs(app).radios = false
        bt?.stop(); ble?.stop(); wd?.stop()
    }

    /** Advertising/serving over radios follows the sharing decision; discovery keeps running for downloads. */
    @Synchronized private fun syncRadios() {
        if (!radiosOn) return
        val srv = server
        val flags = (if (srv != null) BlePayload.FLAG_SHARING else 0) or BlePayload.FLAG_BT_RFCOMM or BlePayload.FLAG_WIFI_DIRECT
        ble?.start(ctx.id.nodeIdHex, flags, advertise = srv != null)
        if (srv != null) {
            bt?.startServer(srv)
            wd?.advertise(ctx.id.nodeIdHex, srv.port, deviceName())
        } else {
            bt?.stopServer(); wd?.unadvertise()
        }
    }

    private fun addWifiDirectRoute(r: PeerRoute, introduce: Boolean) {
        wdRoutes[r.key] = r
        // Tell the group owner our address (it learns it from this connection) so downloads work in both directions.
        if (introduce) Thread { try { PeerClient(r, ctx, 8000).use { it.search(Wire.Query(limit = 1)) } } catch (e: Exception) { } }.apply { isDaemon = true }.start()
    }

    /** A peer connected to our server from inside a Wi-Fi Direct group (192.168.49.0/24): we can reach its server too. */
    private fun learnReverseRoute(remoteKey: String) {
        if (!remoteKey.startsWith("ip:192.168.49.")) return
        addWifiDirectRoute(PeerAddr(remoteKey.removePrefix("ip:"), TCP_PORT, "wifi-direct"), introduce = false)
    }

    // ---------- peers / search / download ----------
    fun discovered() = discovery?.peers().orEmpty()

    /** Every known way to reach a WAVE node, all media, deduplicated, fastest media first. */
    fun routes(): List<PeerRoute> {
        val lan = discovered().map { p -> PeerAddr(p.ip, p.ann.tcpPort, if (p.ip.startsWith("192.168.49.")) "wifi-direct" else "tcp") }
        val all: List<PeerRoute> = lan + manualPeers + wdRoutes.values + bt?.peers?.values.orEmpty()
        return all.distinctBy { it.key }.sortedBy { MediumRank.rank(it.medium) }
    }

    /** Asks every route in parallel; merges identical content (same content ID + publisher) reachable over several routes. */
    fun searchAll(q: Wire.Query): List<SearchHit> {
        val hits = LinkedHashMap<String, SearchHit>()
        val ts = routes().map { r -> Thread {
            try {
                PeerClient(r, ctx, if (r.medium == "bluetooth") 20_000 else 6000).use { c ->
                    val ms = c.search(q)
                    synchronized(hits) { for (m in ms) hits.getOrPut(m.contentIdHex + Hex.encode(m.publisher)) { SearchHit(m, ArrayList()) }.routes.add(r) }
                }
            } catch (e: Exception) { }
        } }
        ts.forEach { it.start() }; ts.forEach { it.join(30_000) }
        return synchronized(hits) { hits.values.toList() }
    }

    /** Downloads from ALL routes that have the content at once (hybrid: e.g. Wi-Fi Direct + Bluetooth). */
    fun startDownload(m: Manifest, routes: List<PeerRoute>): DownloadJob {
        jobs.firstOrNull { it.manifest.contentIdHex == m.contentIdHex && it.state == DownloadJob.State.RUNNING }?.let { return it }
        val job = DownloadJob(m, routes)
        jobs.add(0, job)
        Thread {
            val st = Downloader.download(ctx, m.contentId, routes, cancel = job.cancel) { done, total, bytes -> job.done = done; job.total = total; job.bytes = bytes }
            job.seconds = st.seconds; job.perMedium = st.perMedium.toMap()
            job.done = st.fetched + st.alreadyHad
            job.error = st.error
            job.state = if (st.complete) DownloadJob.State.DONE else DownloadJob.State.FAILED
        }.apply { isDaemon = true; name = "wave-download"; start() }
        return job
    }

    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }
}
