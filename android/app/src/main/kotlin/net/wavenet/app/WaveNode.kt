package net.wavenet.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import net.wavenet.core.Decision
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
import net.wavenet.proto.Wire

class DownloadJob(val manifest: Manifest, val peers: List<PeerAddr>) {
    @Volatile var done = 0
    @Volatile var total = manifest.chunkCount
    @Volatile var bytes = 0L
    @Volatile var state = State.RUNNING
    @Volatile var error = ""
    @Volatile var seconds = 0.0
    @Volatile var sources = 0
    val cancel = AtomicBoolean(false)
    val startedNs = System.nanoTime()
    enum class State { RUNNING, DONE, FAILED }
}

class SearchHit(val manifest: Manifest, val peers: MutableList<PeerAddr>)

/**
 * Process-wide node. Runs ONLY while the app is in the foreground (no hidden background work):
 * onForeground() starts discovery (+ server if sharing is allowed by the user's policy); onBackground() stops everything.
 */
object WaveNode {
    const val TCP_PORT = 47801
    lateinit var ctx: NodeContext; private set
    private lateinit var app: Context
    @Volatile var server: NodeServer? = null; private set
    @Volatile var lastDecision: Decision = Decision.DISABLED; private set
    private var discovery: Discovery? = null
    private var loop: Thread? = null
    private val running = AtomicBoolean(false)
    private var mlock: WifiManager.MulticastLock? = null
    val manualPeers = CopyOnWriteArrayList<PeerAddr>()
    val jobs = CopyOnWriteArrayList<DownloadJob>()

    @Synchronized fun init(c: Context) {
        if (::ctx.isInitialized) return
        app = c.applicationContext
        ctx = NodeContext(KeyVault.loadOrCreate(app), ContentStore(File(app.filesDir, "wave")))
    }

    @Synchronized fun onForeground() {
        if (running.getAndSet(true)) return
        mlock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("wavenet")?.apply { setReferenceCounted(false); acquire() }
        discovery = try { Discovery(ctx.id) } catch (e: Exception) { try { Discovery(ctx.id, port = 0) } catch (x: Exception) { null } }
        loop = Thread {
            var lastAnnounce = 0L; var lastPolicy = 0L
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - lastPolicy > 3000) { applyPolicy(); lastPolicy = now }
                val srv = server
                if (srv != null && now - lastAnnounce > 2000) {
                    discovery?.announce(Announce(srv.port, Build.MODEL ?: "android", roles = 3, contentCount = ctx.store.all().size.toLong()))
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
        stopServer()
        for (j in jobs) j.cancel.set(true)   // downloads stop; they resume from verified chunks next time
        try { mlock?.release() } catch (e: Exception) { }
        mlock = null
    }

    /** Starts/stops the server according to the user's limits (battery, metered data, sharing switch). */
    @Synchronized fun applyPolicy() {
        val d = PolicyEngine.evaluate(Prefs(app).policy, DeviceProbe.state(app), Role.HOST)
        lastDecision = d
        if (d == Decision.ALLOWED && running.get() && server == null) {
            server = try { NodeServer(ctx, "0.0.0.0", TCP_PORT) } catch (e: Exception) { try { NodeServer(ctx, "0.0.0.0", 0) } catch (x: Exception) { null } }
        } else if (d != Decision.ALLOWED) stopServer()
    }

    private fun stopServer() { server?.close(); server = null }

    fun discovered() = discovery?.peers().orEmpty()

    fun peers(): List<PeerAddr> {
        val all = discovered().map { it.addr } + manualPeers
        return all.distinctBy { it.host + ":" + it.port }
    }

    /** Asks every known device in parallel; merges identical content (same content ID) from several devices. */
    fun searchAll(q: Wire.Query): List<SearchHit> {
        val hits = LinkedHashMap<String, SearchHit>()
        val ts = peers().map { p -> Thread {
            try {
                PeerClient(p, ctx, 6000).use { c ->
                    val ms = c.search(q)
                    synchronized(hits) { for (m in ms) hits.getOrPut(m.contentIdHex + Hex.encode(m.publisher)) { SearchHit(m, ArrayList()) }.peers.add(p) }
                }
            } catch (e: Exception) { }
        } }
        ts.forEach { it.start() }; ts.forEach { it.join(10_000) }
        return synchronized(hits) { hits.values.toList() }
    }

    fun startDownload(m: Manifest, peers: List<PeerAddr>): DownloadJob {
        jobs.firstOrNull { it.manifest.contentIdHex == m.contentIdHex && it.state == DownloadJob.State.RUNNING }?.let { return it }
        val job = DownloadJob(m, peers)
        jobs.add(0, job)
        Thread {
            val st = Downloader.download(ctx, m.contentId, peers, cancel = job.cancel) { done, total, bytes -> job.done = done; job.total = total; job.bytes = bytes }
            job.seconds = st.seconds; job.sources = st.perPeer.size
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
