package net.wavenet.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import net.wavenet.core.Decision
import net.wavenet.core.RadioKind
import net.wavenet.core.RadioState
import net.wavenet.proto.Hex
import net.wavenet.proto.LocalState
import net.wavenet.proto.PeerAddr
import net.wavenet.proto.PeerRoute
import net.wavenet.proto.Wire

/** Single-activity UI built in code (AndroidX is not available in the build environment). */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var tab = 0
    private var scanner: NearbyScanner? = null
    // live boxes refreshed every second (re-rendering whole tabs would wipe text the user is typing)
    private var peersBox: LinearLayout? = null
    private var jobsBox: LinearLayout? = null
    private var statusBox: LinearLayout? = null
    private var radiosBox: LinearLayout? = null
    private var pickedUri: Uri? = null
    private var pickedName = ""
    private var pickedMime = ""
    private var exportCid: String? = null
    private var lastHits: List<SearchHit> = emptyList()

    private val categories = listOf("books" to R.string.cat_books, "apps" to R.string.cat_apps, "videos" to R.string.cat_videos,
        "images" to R.string.cat_images, "audio" to R.string.cat_audio, "documents" to R.string.cat_documents,
        "education" to R.string.cat_education, "other" to R.string.cat_other)

    override fun attachBaseContext(base: Context) {
        val lang = Prefs(base).language
        if (lang.isEmpty()) { super.attachBaseContext(base); return }
        val cfg = android.content.res.Configuration(base.resources.configuration)
        val loc = Locale(lang)
        Locale.setDefault(loc); cfg.setLocale(loc); cfg.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WaveNode.init(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        exportCid = savedInstanceState?.getString("export")
        if (!prefs.welcomed) showWelcome() else showMain()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("tab", tab); out.putString("export", exportCid) }
    override fun onStart() { super.onStart(); WaveNode.onForeground(); ui.post(tick) }
    override fun onStop() { super.onStop(); ui.removeCallbacks(tick); scanner?.stop(); WaveNode.onBackground() }

    private val tick = object : Runnable {
        override fun run() { refreshLive(); ui.postDelayed(this, 1000) }
    }

    // ---------- small view helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun s(id: Int) = getString(id)
    private fun text(t: String, size: Float = 15f, bold: Boolean = false, color: Int? = null) = TextView(this).apply {
        text = t; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
        color?.let { setTextColor(it) }
        setPadding(0, dp(4), 0, dp(4)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    private fun header(t: String) = text(t, 18f, true, getColor(R.color.wave_primary)).apply { setPadding(0, dp(14), 0, dp(4)) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
    private fun edit(hint: String, value: String = "", number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(); if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun bg(work: () -> Unit) = Thread { try { work() } catch (e: Exception) { runOnUiThread { toast(s(R.string.error_prefix) + ": " + e.message) } } }.start()

    private fun human(n: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB"); var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    // ---------- screens ----------
    private fun showWelcome() {
        val box = vbox().apply { setGravity(Gravity.CENTER); setPadding(dp(28), dp(28), dp(28), dp(28)) }
        box.addView(text(s(R.string.app_name), 32f, true, getColor(R.color.wave_primary)).apply { gravity = Gravity.CENTER })
        box.addView(text(s(R.string.welcome_title), 20f, true).apply { gravity = Gravity.CENTER })
        box.addView(text(s(R.string.welcome_body), 16f).apply { gravity = Gravity.CENTER })
        box.addView(button(s(R.string.welcome_continue)) { prefs.welcomed = true; showMain() })
        setContentView(box)
    }

    private fun showMain() {
        val root = vbox()
        root.addView(text(s(R.string.app_name), 20f, true, 0xFFFFFFFF.toInt()).apply {
            setBackgroundColor(getColor(R.color.wave_primary)); setPadding(dp(16), dp(14), dp(16), dp(14)) })
        val scroll = ScrollView(this)
        content = vbox().apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.string.tab_home, R.string.tab_store, R.string.tab_publish, R.string.tab_library, R.string.tab_settings).forEachIndexed { i, id ->
            nav.addView(Button(this).apply { text = s(id); textSize = 11f; setOnClickListener { tab = i; scanner?.stop(); render() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        setContentView(root)
        render()
    }

    private fun render() {
        content.removeAllViews(); peersBox = null; jobsBox = null; statusBox = null; radiosBox = null
        when (tab) { 0 -> home(); 1 -> store(); 2 -> publish(); 3 -> library(); else -> settings() }
        refreshLive()
    }

    private fun mediumName(m: String) = s(when (m) { "wifi-direct" -> R.string.medium_wifi_direct; "bluetooth" -> R.string.medium_bluetooth; else -> R.string.medium_tcp })

    private fun requestRadios() {
        val needed = DeviceProbe.radios(this).filter { it.kind == RadioKind.BT_CLASSIC || it.kind == RadioKind.BLE || it.kind == RadioKind.WIFI_DIRECT }
            .flatMap { it.missingPermissions }.distinct()
        if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), REQ_RADIOS); return }
        enableRadios()
    }

    private fun enableRadios() {
        if (WaveNode.bt?.enabled == false) try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { }
        bg { WaveNode.startRadios() }
        ui.postDelayed({ if (tab == 0) render() }, 500)
    }

    private fun decisionText(d: Decision) = s(when (d) {
        Decision.ALLOWED -> R.string.decision_allowed; Decision.DISABLED -> R.string.decision_disabled
        Decision.ROLE_OFF -> R.string.decision_role_off; Decision.LOW_BATTERY -> R.string.decision_low_battery
        Decision.METERED -> R.string.decision_metered; Decision.DATA_CAP -> R.string.decision_data_cap
        Decision.STORAGE_CAP -> R.string.decision_storage_cap })

    // ----- Home -----
    private fun home() {
        content.addView(header(s(R.string.sharing_title)))
        val sw = Switch(this).apply { text = s(R.string.sharing_title); isChecked = prefs.policy.sharingEnabled }
        sw.setOnCheckedChangeListener { _, on -> prefs.policy = prefs.policy.copy(sharingEnabled = on); bg { WaveNode.applyPolicy() } }
        content.addView(sw)
        statusBox = vbox().also { content.addView(it) }
        content.addView(text(s(R.string.keep_open), 13f, color = getColor(R.color.wave_warn)))
        content.addView(header(s(R.string.radios_title)))
        radiosBox = vbox().also { content.addView(it) }
        content.addView(header(s(R.string.node_id)))
        content.addView(text(WaveNode.ctx.id.nodeIdHex.chunked(16).joinToString(" "), 12f).apply { typeface = Typeface.MONOSPACE })
        content.addView(header(s(R.string.section_radios)))
        for (r in DeviceProbe.radios(this)) {
            val name = s(when (r.kind) { RadioKind.WIFI_LAN -> R.string.radio_wifi_lan; RadioKind.WIFI_DIRECT -> R.string.radio_wifi_direct
                RadioKind.WIFI_AWARE -> R.string.radio_wifi_aware; RadioKind.BLE -> R.string.radio_ble; RadioKind.BT_CLASSIC -> R.string.radio_bt_classic })
            val st = s(when (r.state) { RadioState.UNSUPPORTED -> R.string.status_unsupported; RadioState.NEEDS_PERMISSION -> R.string.status_needs_permission
                RadioState.OFF -> R.string.status_off; RadioState.READY -> R.string.status_ready; RadioState.DISCOVERY_ONLY -> R.string.status_discovery_only; RadioState.PLANNED -> R.string.status_planned })
            val color = getColor(when (r.state) { RadioState.READY -> R.color.wave_ok; RadioState.UNSUPPORTED -> R.color.wave_off; else -> R.color.wave_warn })
            content.addView(text("$name — $st", 14f, color = color))
        }
        val found = vbox(); val status = text("", 13f)
        content.addView(button(s(R.string.nearby_scan)) {
            val needed = DeviceProbe.radios(this).filter { it.kind == RadioKind.BLE || it.kind == RadioKind.WIFI_DIRECT }.flatMap { it.missingPermissions }.distinct()
            if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), REQ_SCAN); return@button }
            status.text = s(R.string.nearby_scanning); found.removeAllViews()
            scanner?.stop()
            scanner = NearbyScanner(this) { items -> found.removeAllViews(); items.forEach { found.addView(text(it, 13f)) } }.also { it.start() }
        })
        content.addView(status); content.addView(found)
        content.addView(text(s(R.string.nearby_note), 12f))
    }

    // ----- Store -----
    private fun store() {
        content.addView(header(s(R.string.store_devices)))
        peersBox = vbox().also { content.addView(it) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addr = edit(s(R.string.store_add_peer))
        addRow.addView(addr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(button(s(R.string.store_add)) {
            val v = addr.text.toString().trim(); val i = v.lastIndexOf(':')
            val port = if (i > 0) v.substring(i + 1).toIntOrNull() else WaveNode.TCP_PORT
            val host = if (i > 0) v.substring(0, i) else v
            if (host.isEmpty() || port == null || port !in 1..65535) toast(s(R.string.store_bad_address))
            else { WaveNode.manualPeers.add(PeerAddr(host, port)); addr.setText(""); refreshLive() }
        })
        content.addView(addRow)
        content.addView(header(s(R.string.store_search)))
        val q = edit(s(R.string.store_search_hint))
        content.addView(q)
        val results = vbox()
        content.addView(button(s(R.string.store_search)) {
            results.removeAllViews(); results.addView(text(s(R.string.store_searching), 13f))
            val query = q.text.toString()
            bg {
                val hits = WaveNode.searchAll(Wire.Query(query))
                runOnUiThread { lastHits = hits; showHits(results, hits) }
            }
        })
        content.addView(results)
        if (lastHits.isNotEmpty()) showHits(results, lastHits)
        content.addView(header(s(R.string.store_transfers)))
        jobsBox = vbox().also { content.addView(it) }
        content.addView(text(s(R.string.keep_open), 12f, color = getColor(R.color.wave_warn)))
    }

    private fun showHits(box: LinearLayout, hits: List<SearchHit>) {
        box.removeAllViews()
        box.addView(text(s(R.string.store_results) + " (" + hits.size + ")", 15f, true))
        if (hits.isEmpty()) box.addView(text(s(R.string.store_none), 14f))
        for (h in hits) {
            val m = h.manifest
            val card = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            card.addView(text(m.title, 16f, true))
            if (m.description.isNotEmpty()) card.addView(text(m.description, 13f))
            card.addView(text(listOf(m.category, m.fileName, human(m.size), s(R.string.from_devices) + " " + h.routes.size + " " + s(R.string.devices_word) +
                " (" + h.routes.map { mediumName(it.medium) }.distinct().joinToString(", ") + ")").filter { it.isNotEmpty() }.joinToString(" · "), 12f))
            val local = WaveNode.ctx.store.get(m.contentId)
            if (local != null && local.state != LocalState.REMOTE && WaveNode.ctx.store.hasComplete(m.contentId))
                card.addView(text("✓ " + s(R.string.lib_downloaded), 13f, color = getColor(R.color.wave_ok)))
            else card.addView(button(s(R.string.download)) { WaveNode.startDownload(m, h.routes); refreshLive() })
            box.addView(card)
        }
    }

    // ----- Publish -----
    private fun publish() {
        content.addView(header(s(R.string.publish_title)))
        val chosen = text(if (pickedUri == null) s(R.string.publish_none) else pickedName, 14f)
        content.addView(button(s(R.string.publish_pick)) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK)
        })
        content.addView(chosen)
        val title = edit(s(R.string.publish_field_title), pickedName.substringBeforeLast('.'))
        val desc = edit(s(R.string.publish_field_desc)); val kw = edit(s(R.string.publish_field_keywords)); val lic = edit(s(R.string.publish_field_license))
        content.addView(title); content.addView(desc)
        content.addView(text(s(R.string.publish_field_category), 13f))
        val cat = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories.map { s(it.second) }) }
        content.addView(cat); content.addView(kw); content.addView(lic)
        val priv = CheckBox(this).apply { text = s(R.string.publish_private) }.also { content.addView(it) }
        val redist = CheckBox(this).apply { text = s(R.string.publish_redistribute); isChecked = true }.also { content.addView(it) }
        val status = text("", 14f)
        content.addView(button(s(R.string.publish_do)) {
            val uri = pickedUri ?: run { toast(s(R.string.publish_need_file)); return@button }
            status.text = s(R.string.publish_working)
            val meta = arrayOf(title.text.toString(), desc.text.toString(), categories[cat.selectedItemPosition].first, kw.text.toString(), lic.text.toString())
            val isPriv = priv.isChecked; val allow = redist.isChecked; val name = pickedName; val mime = pickedMime
            bg {
                val m = contentResolver.openInputStream(uri)!!.use {
                    WaveNode.ctx.store.import(it, name, WaveNode.ctx.id, title = meta[0], description = meta[1], category = meta[2],
                        keywords = meta[3], license = meta[4], mime = mime, isPrivate = isPriv, allowRedistribution = allow)
                }
                runOnUiThread { status.text = s(R.string.publish_done) + ": " + m.title + " (" + human(m.size) + ")\nSHA-256 " + m.contentIdHex; pickedUri = null; pickedName = "" }
            }
        })
        content.addView(status)
        content.addView(text(s(R.string.publish_note), 12f))
    }

    // ----- Library -----
    private fun library() {
        val entries = WaveNode.ctx.store.all().filter { it.state != LocalState.REMOTE && WaveNode.ctx.store.hasComplete(it.manifest.contentId) }
            .sortedByDescending { it.manifest.createdMs }
        content.addView(header(s(R.string.tab_library) + " (" + entries.size + ")"))
        if (entries.isEmpty()) content.addView(text(s(R.string.library_empty), 14f))
        for (e in entries) {
            val m = e.manifest
            val card = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            card.addView(text(m.title, 16f, true))
            card.addView(text(listOf(s(if (e.state == LocalState.PUBLISHED) R.string.lib_published else R.string.lib_downloaded),
                if (m.isPrivate) s(R.string.private_label) else "", m.category, human(m.size)).filter { it.isNotEmpty() }.joinToString(" · "), 12f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button(s(R.string.lib_save)) {
                exportCid = m.contentIdHex
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(m.mime.ifEmpty { "application/octet-stream" }).putExtra(Intent.EXTRA_TITLE, m.fileName), REQ_SAVE)
            })
            if (e.state == LocalState.PUBLISHED) row.addView(button(s(R.string.lib_unpublish)) {
                WaveNode.ctx.store.remove(m.contentId, m.publisher); render()
            })
            card.addView(row)
            content.addView(card)
        }
    }

    // ----- Settings -----
    private fun settings() {
        val p = prefs.policy
        content.addView(header(s(R.string.res_title)))
        fun num(label: Int, v: Int): EditText { content.addView(text(s(label), 13f)); return edit("", v.toString(), number = true).also { content.addView(it) } }
        val battery = num(R.string.res_min_battery, p.minBatteryPercent)
        val data = num(R.string.res_max_data, p.maxMobileDataMbPerDay)
        val storage = num(R.string.res_max_storage, p.maxStorageMb)
        val metered = CheckBox(this).apply { text = s(R.string.res_allow_metered); isChecked = p.allowMetered }.also { content.addView(it) }
        val decision = text("", 14f, true)
        content.addView(button(s(R.string.res_save)) {
            prefs.policy = prefs.policy.copy(minBatteryPercent = battery.text.toString().toIntOrNull() ?: p.minBatteryPercent,
                maxMobileDataMbPerDay = data.text.toString().toIntOrNull() ?: p.maxMobileDataMbPerDay,
                maxStorageMb = storage.text.toString().toIntOrNull() ?: p.maxStorageMb, allowMetered = metered.isChecked)
            bg { WaveNode.applyPolicy(); runOnUiThread { decision.text = s(R.string.res_current) + ": " + decisionText(WaveNode.lastDecision) } }
            toast(s(R.string.res_saved))
        })
        content.addView(decision)
        content.addView(header(s(R.string.settings_language)))
        val group = RadioGroup(this)
        listOf("" to s(R.string.settings_lang_system), "ar" to "العربية", "en" to "English").forEach { (code, label) ->
            group.addView(RadioButton(this).apply {
                text = label; id = View.generateViewId(); isChecked = prefs.language == code
                setOnClickListener { if (prefs.language != code) { prefs.language = code; recreate() } }
            })
        }
        content.addView(group)
        content.addView(header(s(R.string.settings_about)))
        content.addView(text(s(R.string.settings_about_body) + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", 13f))
    }

    // ---------- live refresh ----------
    private fun refreshLive() {
        radiosBox?.let { b ->
            b.removeAllViews()
            if (!WaveNode.radiosOn) {
                b.addView(text(s(R.string.radios_off_note), 13f))
                b.addView(button(s(R.string.radios_enable)) { requestRadios() })
                return@let
            }
            val bt = WaveNode.bt
            if (bt != null && !bt.enabled) b.addView(button(s(R.string.bt_turn_on)) { enableRadios() })
            b.addView(text(s(if (bt?.serverRunning == true) R.string.bt_server_on else R.string.bt_server_off), 13f,
                color = getColor(if (bt?.serverRunning == true) R.color.wave_ok else R.color.wave_warn)))
            b.addView(text(s(R.string.bt_peers) + ": " + (bt?.peers?.values?.joinToString(", ") { it.label }?.ifEmpty { "—" } ?: "—"), 13f))
            b.addView(text(s(R.string.ble_nearby) + ": " + (WaveNode.ble?.recent()?.size ?: 0), 13f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button(s(R.string.bt_visible)) { WaveNode.bt?.requestDiscoverable(this) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button(s(R.string.radios_rescan)) { bg { WaveNode.bt?.scan(); WaveNode.wd?.rediscover() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            b.addView(row)
            val wd = WaveNode.wd
            b.addView(text(s(R.string.wd_title), 14f, true))
            if (wd != null && wd.connectedTo.isNotEmpty()) {
                b.addView(text(s(R.string.wd_connected) + " (" + wd.connectedTo + ")", 13f, color = getColor(R.color.wave_ok)))
                b.addView(button(s(R.string.wd_disconnect)) { wd.disconnect() })
            }
            val wdPeers = wd?.peers?.values?.toList().orEmpty()
            if (wdPeers.isEmpty()) b.addView(text(s(R.string.wd_none), 13f))
            for (p in wdPeers) {
                val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r2.addView(text("● " + p.hint.name.ifEmpty { p.device.deviceName }, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                r2.addView(button(s(R.string.wd_connect)) { wd?.connect(p.device.deviceAddress); toast(s(R.string.wd_note)) })
                b.addView(r2)
            }
            if (!bt?.lastError.isNullOrEmpty() || !wd?.lastError.isNullOrEmpty())
                b.addView(text(listOf(bt?.lastError, wd?.lastError).filter { !it.isNullOrEmpty() }.joinToString(" · "), 12f, color = getColor(R.color.wave_off)))
            b.addView(button(s(R.string.radios_disable)) { bg { WaveNode.stopRadios() }; ui.postDelayed({ refreshLive() }, 300) })
        }
        statusBox?.let { b ->
            b.removeAllViews()
            val srv = WaveNode.server
            val d = WaveNode.lastDecision
            b.addView(text(if (srv != null) s(R.string.sharing_on) else if (d == Decision.DISABLED) s(R.string.sharing_off)
                           else s(R.string.sharing_paused) + ": " + decisionText(d), 14f,
                color = getColor(if (srv != null) R.color.wave_ok else R.color.wave_warn)))
            val ips = WaveNode.localAddresses()
            b.addView(text(s(R.string.my_address) + ": " + (if (ips.isEmpty()) s(R.string.no_network)
                           else ips.joinToString(", ") { it + ":" + (srv?.port ?: WaveNode.TCP_PORT) }), 13f))
        }
        peersBox?.let { b ->
            b.removeAllViews()
            val routes = WaveNode.routes()
            if (routes.isEmpty()) b.addView(text(s(R.string.store_no_devices), 13f))
            val names = WaveNode.discovered().associate { "tcp:" + it.ip + ":" + it.ann.tcpPort to it.ann.name }
            for (r in routes) b.addView(text("● " + (names[r.key] ?: r.toString()) + "  — " + s(R.string.via) + " " + mediumName(r.medium), 14f))
        }
        jobsBox?.let { b ->
            b.removeAllViews()
            for (j in WaveNode.jobs) {
                val m = j.manifest
                b.addView(text(m.title, 14f, true))
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = maxOf(1, j.total); progress = j.done }
                b.addView(bar)
                val secs = (System.nanoTime() - j.startedNs) / 1e9
                val line = when (j.state) {
                    DownloadJob.State.RUNNING -> s(R.string.downloading) + " " + j.done + "/" + j.total + " · " + s(R.string.speed) + " " +
                        (if (secs > 0) human((j.bytes / secs).toLong()) + "/s" else "-")
                    DownloadJob.State.DONE -> s(R.string.download_done) + " · " + human(m.size) + " · " + String.format(Locale.US, "%.1fs", j.seconds) +
                        " · " + j.perMedium.entries.joinToString(", ") { mediumName(it.key) + " " + it.value }
                    DownloadJob.State.FAILED -> s(R.string.download_failed) + ": " + j.error
                }
                b.addView(text(line, 12f, color = getColor(when (j.state) { DownloadJob.State.DONE -> R.color.wave_ok; DownloadJob.State.FAILED -> R.color.wave_off; else -> R.color.wave_primary })))
                when (j.state) {
                    DownloadJob.State.RUNNING -> b.addView(button(s(R.string.cancel)) { j.cancel.set(true) })
                    DownloadJob.State.FAILED -> b.addView(button(s(R.string.download_resume)) {
                        WaveNode.jobs.remove(j); WaveNode.startDownload(m, (j.peers + WaveNode.routes()).distinctBy { it.key }) })
                    else -> {}
                }
            }
        }
    }

    // ---------- results ----------
    @Deprecated("framework Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        if (req == REQ_PICK) {
            pickedUri = uri
            pickedMime = contentResolver.getType(uri) ?: ""
            pickedName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null } ?: "file"
            if (tab == 2) render()
        } else if (req == REQ_SAVE) {
            val cid = exportCid ?: return
            bg {
                val blob = WaveNode.ctx.store.blob(Hex.decode(cid))
                contentResolver.openOutputStream(uri)!!.use { out -> blob.inputStream().use { it.copyTo(out) } }
                runOnUiThread { toast(s(R.string.lib_saved)) }
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_RADIOS) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) enableRadios() else toast(s(R.string.permission_denied))
            return
        }
        if (code != REQ_SCAN) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) render() else toast(s(R.string.permission_denied))
    }

    companion object { private const val REQ_SCAN = 7; private const val REQ_PICK = 8; private const val REQ_SAVE = 9; private const val REQ_RADIOS = 10 }
}
