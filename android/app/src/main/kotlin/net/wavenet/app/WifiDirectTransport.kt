package net.wavenet.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import net.wavenet.core.PeerHint
import net.wavenet.core.RadioIds
import net.wavenet.core.WifiDirectTxt
import net.wavenet.proto.PeerAddr

/**
 * Wi-Fi Direct transport. WAVE nodes publish a DNS-SD service (_wavenet._tcp, TXT: node-id prefix + port) so phones can see
 * which nearby devices run WAVE BEFORE connecting. After the user connects (Android shows an invitation on the other phone),
 * the P2P group is an IP network: the client reaches the group owner at its address with the same TCP + TLS 1.3 protocol.
 * The group owner learns the client's address when the client connects (NodeServer.onPeer).
 */
class WifiDirectTransport(private val ctx: Context, private val onRoute: (PeerAddr) -> Unit) {
    private val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    class WdPeer(val device: WifiP2pDevice, val hint: PeerHint)
    val peers = ConcurrentHashMap<String, WdPeer>()   // device address -> WAVE service seen
    @Volatile var connectedTo = ""; private set
    @Volatile var isGroupOwner = false; private set
    @Volatile var lastError = ""; private set
    val available: Boolean get() = manager != null

    @Volatile var advertising = false; private set
    private var pendingDevice = ""

    /** Publishes this node as a Wi-Fi Direct WAVE service — only while sharing is allowed. */
    fun advertise(nodeIdHex: String, port: Int, name: String) {
        if (advertising) return
        val m = manager ?: return; val ch = channel ?: return
        try {
            m.clearLocalServices(ch, null)
            m.addLocalService(ch, WifiP2pDnsSdServiceInfo.newInstance("WAVE-" + nodeIdHex.take(8), RadioIds.DNSSD_TYPE,
                WifiDirectTxt.build(nodeIdHex, port, name)), null)
            advertising = true
        } catch (e: SecurityException) { lastError = "wifi-direct permission" }
    }

    fun unadvertise() {
        if (!advertising) return
        try { channel?.let { manager?.clearLocalServices(it, null) } } catch (e: Exception) { }
        advertising = false
    }

    /** Starts looking for nearby WAVE services (works whether or not this phone shares). */
    fun start(nodeIdHex: String) {
        val m = manager ?: return
        try {
            val ch = channel ?: m.initialize(ctx, Looper.getMainLooper(), null).also { channel = it }
            m.setDnsSdResponseListeners(ch, { _, _, _ -> }, { _, txt, device ->
                WifiDirectTxt.parse(txt)?.let { h -> if (!nodeIdHex.startsWith(h.nodeIdPrefix)) peers[device.deviceAddress] = WdPeer(device, h) }
            })
            m.clearServiceRequests(ch, null)
            m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), null)
            m.discoverServices(ch, null)
            if (receiver == null) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) { if (i.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) refreshConnection() }
                }
                receiver = r
                ctx.registerReceiver(r, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
            }
        } catch (e: SecurityException) { lastError = "wifi-direct permission" } catch (e: Exception) { lastError = "wifi-direct: ${e.message}" }
    }

    /** Re-runs service discovery (Android stops it after ~2 minutes). */
    fun rediscover() { try { channel?.let { manager?.discoverServices(it, null) } } catch (e: Exception) { } }

    fun connect(deviceAddress: String) {
        val m = manager ?: return; val ch = channel ?: return
        pendingDevice = deviceAddress
        val cfg = WifiP2pConfig().apply { this.deviceAddress = deviceAddress; wps.setup = WpsInfo.PBC }
        try {
            m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { lastError = "" }
                override fun onFailure(reason: Int) { lastError = "wifi-direct connect failed ($reason)" }
            })
        } catch (e: SecurityException) { lastError = "wifi-direct permission" }
    }

    private fun refreshConnection() {
        val m = manager ?: return; val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                    isGroupOwner = info.isGroupOwner
                    val goIp = info.groupOwnerAddress.hostAddress ?: return@requestConnectionInfo
                    connectedTo = goIp
                    if (!info.isGroupOwner) {
                        val port = peers[pendingDevice]?.hint?.port ?: WaveNode.TCP_PORT
                        onRoute(PeerAddr(goIp, port, "wifi-direct"))
                    }
                } else { connectedTo = ""; isGroupOwner = false }
            }
        } catch (e: SecurityException) { lastError = "wifi-direct permission" }
    }

    fun disconnect() { try { channel?.let { manager?.removeGroup(it, null) } } catch (e: Exception) { } }

    fun stop() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) { } }
        receiver = null
        try { channel?.let { manager?.clearLocalServices(it, null); manager?.clearServiceRequests(it, null) } } catch (e: Exception) { }
        advertising = false
    }
}
