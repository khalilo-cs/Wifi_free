package net.wavenet.app

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Short, user-started discovery of nearby devices (BLE advertisements + Wi-Fi Direct peers).
 * It only LISTS devices; WAVE transfer over these media is not implemented yet. Stops automatically.
 */
class NearbyScanner(private val ctx: Context, private val onUpdate: (List<String>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, String>()
    private var bleCb: ScanCallback? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    fun start(durationMs: Long = 12_000) {
        stop()
        startBle()
        startWifiDirect()
        main.postDelayed({ stop() }, durationMs)
    }

    private fun publish(key: String, label: String) {
        main.post { if (found.put(key, label) == null) onUpdate(found.values.toList()) }
    }

    private fun startBle() { try {
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
        if (scanner != null) {
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val name = try { r.device.name } catch (e: SecurityException) { null }
                    publish("ble:" + r.device.address, "BLE  " + (name ?: r.device.address) + "  (" + r.rssi + " dBm)")
                }
            }
            bleCb = cb
            scanner.startScan(cb)
        }
    } catch (e: SecurityException) { } }

    private fun startWifiDirect() { try {
        val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m != null) {
            val ch = m.initialize(ctx, Looper.getMainLooper(), null)
            p2p = m; channel = ch
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) return
                    try {
                        m.requestPeers(ch) { list: WifiP2pDeviceList ->
                            for (d in list.deviceList) publish("p2p:" + d.deviceAddress, "Wi-Fi Direct  " + d.deviceName)
                        }
                    } catch (e: SecurityException) { }
                }
            }
            p2pReceiver = rcv
            ctx.registerReceiver(rcv, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
            m.discoverPeers(ch, null)
        }
    } catch (e: SecurityException) { } }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        try {
            bleCb?.let { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (e: Exception) { }
        bleCb = null
        p2pReceiver?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) { } }
        p2pReceiver = null
        try { channel?.let { p2p?.stopPeerDiscovery(it, null) } } catch (e: Exception) { }
        channel = null
    }
}
