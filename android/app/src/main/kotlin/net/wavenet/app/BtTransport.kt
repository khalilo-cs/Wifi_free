package net.wavenet.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcelable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.wavenet.core.RadioIds
import net.wavenet.core.UuidMatch
import net.wavenet.proto.ByteChannel
import net.wavenet.proto.Link
import net.wavenet.proto.NodeServer
import net.wavenet.proto.PeerRoute
import net.wavenet.proto.Tls
import net.wavenet.proto.TransportError
import net.wavenet.proto.Watchdog

private val WAVE_UUID: UUID = UUID.fromString(RadioIds.BT_RFCOMM_UUID)

/** Route to a WAVE node over Bluetooth Classic RFCOMM (insecure = no pairing; security = TLS 1.3 + WAVE handshake). */
class BtRoute(private val adapter: BluetoothAdapter, val address: String, val label: String) : PeerRoute {
    override val key get() = "bt:$address"
    override val medium get() = "bluetooth"
    override fun open(timeoutMs: Int): Link {
        val dev = adapter.getRemoteDevice(address)
        try { adapter.cancelDiscovery() } catch (e: SecurityException) { }   // discovery slows connections down
        val sock = try { dev.createInsecureRfcommSocketToServiceRecord(WAVE_UUID) } catch (e: Exception) { throw TransportError("bluetooth: ${e.message}") }
        val guard = Watchdog.after(maxOf(timeoutMs, 12_000).toLong()) { sock.close() }   // connect() has no timeout of its own
        try { sock.connect() } catch (e: Exception) { try { sock.close() } catch (x: Exception) { }; throw TransportError("bluetooth connect failed: ${e.message}") }
        finally { guard.cancel(false) }
        val ch = ByteChannel(sock.inputStream, sock.outputStream, "tls+bt:$label", "bt:$address", null, { sock.close() }, "bluetooth")
        return Tls.clientWrap(ch, timeoutMs)
    }
    override fun toString() = "bt:$label"
}

/**
 * Bluetooth Classic transport: RFCOMM server feeding NodeServer.serveChannel, and discovery of WAVE nodes via SDP
 * (paired devices + classic inquiry). Every call is guarded: missing permission/hardware disables BT, never crashes.
 */
class BtTransport(private val ctx: Context) {
    val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val peers = ConcurrentHashMap<String, BtRoute>()   // address -> route (only devices advertising the WAVE RFCOMM service)
    @Volatile var serverRunning = false; private set
    @Volatile var lastError = ""; private set
    private var ss: BluetoothServerSocket? = null
    private var receiver: BroadcastReceiver? = null
    private val candidates = ConcurrentHashMap<String, BluetoothDevice>()

    val enabled: Boolean get() = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }

    fun startServer(server: NodeServer) {
        if (serverRunning || adapter == null || !enabled) return
        val s = try { adapter.listenUsingInsecureRfcommWithServiceRecord(RadioIds.BT_SERVICE_NAME, WAVE_UUID) }
            catch (e: Exception) { lastError = "bt listen: ${e.message}"; return }
        ss = s; serverRunning = true
        Thread {
            while (serverRunning) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                val addr = try { sock.remoteDevice.address } catch (e: Exception) { "?" }
                val ch = ByteChannel(sock.inputStream, sock.outputStream, "tls+bt:$addr", "bt:$addr", null, { sock.close() }, "bluetooth")
                Thread { server.serveChannel(ch) }.apply { isDaemon = true; name = "wave-bt-conn" }.start()
            }
            serverRunning = false
        }.apply { isDaemon = true; name = "wave-bt-accept"; start() }
    }

    fun stopServer() { serverRunning = false; try { ss?.close() } catch (e: Exception) { }; ss = null }

    private fun consider(d: BluetoothDevice, uuids: Array<out Parcelable>?) {
        val wave = uuids?.any { UuidMatch.matches(it.toString(), RadioIds.BT_RFCOMM_UUID) } == true
        if (wave && adapter != null) {
            val name = try { d.name } catch (e: SecurityException) { null } ?: d.address
            peers[d.address] = BtRoute(adapter, d.address, name)
        }
    }

    /** Finds WAVE nodes: checks paired devices, runs a classic inquiry, then asks each found device for its services (SDP). */
    fun scan() {
        val a = adapter ?: return
        if (!enabled) return
        stopScan()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    when (i.action) {
                        BluetoothDevice.ACTION_FOUND -> (i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))?.let { candidates[it.address] = it }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> for (d in candidates.values) d.fetchUuidsWithSdp()
                        BluetoothDevice.ACTION_UUID -> {
                            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                            consider(d, i.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID))
                        }
                    }
                } catch (e: SecurityException) { lastError = "bt permission" }
            }
        }
        receiver = r
        ctx.registerReceiver(r, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothDevice.ACTION_UUID) })
        try {
            for (d in a.bondedDevices.orEmpty()) { consider(d, d.uuids); d.fetchUuidsWithSdp() }
            a.startDiscovery()
        } catch (e: SecurityException) { lastError = "bt permission" }
    }

    fun stopScan() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) { } }
        receiver = null
        try { adapter?.cancelDiscovery() } catch (e: SecurityException) { }
    }

    /** Lets unpaired phones find this one for 5 minutes (Android asks the user to confirm). */
    fun requestDiscoverable(activity: Activity) {
        try {
            activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } catch (e: Exception) { lastError = "bt discoverable: ${e.message}" }
    }

    fun stop() { stopScan(); stopServer() }
}
