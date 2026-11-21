package net.wavenet.app

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.concurrent.ConcurrentHashMap
import net.wavenet.core.BlePayload
import net.wavenet.core.PeerHint
import net.wavenet.core.RadioIds

/**
 * Low-power BLE beacon: tells nearby phones "a WAVE node is here" (node-ID prefix + which media it offers).
 * Discovery hint only — BLE is not used to carry files. Hints are never trusted; the TLS/WAVE handshake authenticates.
 */
class BleBeacon(ctx: Context) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val uuid = ParcelUuid.fromString(RadioIds.BLE_SERVICE_UUID)
    class Seen(val hint: PeerHint, val rssi: Int, val at: Long)
    val nearby = ConcurrentHashMap<String, Seen>()   // node-id prefix -> last sighting
    @Volatile var advertising = false; private set
    private var adv: AdvertiseCallback? = null
    private var scanCb: ScanCallback? = null

    /** Scans for WAVE beacons always; advertises our own beacon only when `advertise` (i.e. sharing is allowed). */
    fun start(nodeIdHex: String, flags: Int, advertise: Boolean) {
        val a = adapter ?: return
        try {
            if (!advertise) stopAdvertising()
            val advertiser = a.bluetoothLeAdvertiser
            if (advertise && advertiser != null && adv == null) {
                val cb = object : AdvertiseCallback() {
                    override fun onStartSuccess(s: AdvertiseSettings) { advertising = true }
                    override fun onStartFailure(code: Int) { advertising = false }
                }
                adv = cb
                advertiser.startAdvertising(
                    AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).setTimeout(0).build(),
                    AdvertiseData.Builder().addServiceUuid(uuid).setIncludeDeviceName(false).build(),
                    AdvertiseData.Builder().addServiceData(uuid, BlePayload.build(nodeIdHex, flags)).setIncludeDeviceName(false).build(),
                    cb)
            }
            val scanner = a.bluetoothLeScanner
            if (scanner != null && scanCb == null) {
                val cb = object : ScanCallback() {
                    override fun onScanResult(type: Int, r: ScanResult) {
                        val hint = BlePayload.parse(r.scanRecord?.getServiceData(uuid)) ?: return
                        if (nodeIdHex.startsWith(hint.nodeIdPrefix)) return
                        nearby[hint.nodeIdPrefix] = Seen(hint, r.rssi, System.currentTimeMillis())
                    }
                }
                scanCb = cb
                scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), cb)
            }
        } catch (e: SecurityException) { stop() } catch (e: IllegalStateException) { stop() }
    }

    fun recent(windowMs: Long = 30_000): List<Seen> = nearby.values.filter { System.currentTimeMillis() - it.at < windowMs }

    fun stopAdvertising() {
        try { adv?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (e: Exception) { }
        adv = null; advertising = false
    }

    fun stop() {
        try { adv?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (e: Exception) { }
        try { scanCb?.let { adapter?.bluetoothLeScanner?.stopScan(it) } } catch (e: Exception) { }
        adv = null; scanCb = null; advertising = false
    }
}
