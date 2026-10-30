package com.example.vpnapp

import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var connectButton: Button
    private lateinit var importButton: Button
    private lateinit var autoConnectBox: CheckBox
    private lateinit var autoSwitchBox: CheckBox
    private lateinit var serverList: ListView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var bundled: List<Server> = emptyList()
    private var imported: List<Server> = emptyList()
    private var displayed: List<Server> = emptyList()
    private var selected: Server? = null
    private var pending: Server? = null
    private var busy = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTime = 0L

    // Android shows a one-time system dialog asking the user to allow this app's VPN.
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val server = pending
            if (result.resultCode == RESULT_OK && server != null) {
                startTunnel(server)
            } else {
                toast(R.string.vpn_permission_denied)
            }
        }

    // Lets the user pick a WireGuard .conf file from the phone.
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askNameAndImport(uri)
        }

    private val ticker = object : Runnable {
        override fun run() {
            updateSpeed()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        VpnController.init(this)

        statusText = findViewById(R.id.statusText)
        speedText = findViewById(R.id.speedText)
        connectButton = findViewById(R.id.connectButton)
        importButton = findViewById(R.id.importButton)
        autoConnectBox = findViewById(R.id.autoConnectBox)
        autoSwitchBox = findViewById(R.id.autoSwitchBox)
        serverList = findViewById(R.id.serverList)

        bundled = try {
            ServerRepository.loadBundled(this)
        } catch (e: Exception) {
            toast(R.string.servers_load_error)
            emptyList()
        }
        imported = ServerRepository.loadImported(this)

        selected = all().firstOrNull { it.id == prefs.getString(KEY_LAST_ID, null) && !it.premium }
            ?: all().firstOrNull { !it.premium }

        autoConnectBox.isChecked = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        autoSwitchBox.isChecked = prefs.getBoolean(KEY_AUTO_SWITCH, false)
        autoConnectBox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_CONNECT, checked).apply()
        }
        autoSwitchBox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_SWITCH, checked).apply()
        }

        connectButton.setOnClickListener { onConnectClicked() }
        importButton.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        serverList.setOnItemClickListener { _, _, position, _ -> onServerClicked(displayed[position]) }
        serverList.setOnItemLongClickListener { _, _, position, _ ->
            onServerLongPressed(displayed[position])
            true
        }

        VpnController.onUnexpectedDrop = { runOnUiThread { handleDrop() } }

        renderList()
        refreshUi()

        // Auto-connect only when VPN permission was already granted, so the app never shows a dialog by itself.
        val server = selected
        if (prefs.getBoolean(KEY_AUTO_CONNECT, false) &&
            !VpnController.isUp() &&
            server != null &&
            VpnService.prepare(this) == null
        ) {
            startTunnel(server)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun all(): List<Server> = bundled + imported

    private fun onServerClicked(server: Server) {
        if (server.premium) {
            // Paid servers are only marked here. Real paid access must be enforced by a backend.
            toast(R.string.premium_locked)
            return
        }
        selected = server
        renderList()
    }

    private fun onServerLongPressed(server: Server) {
        if (!server.imported) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(server.name)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                imported = imported.filterNot { it.id == server.id }
                ServerRepository.saveImported(this, imported)
                if (selected?.id == server.id) {
                    selected = all().firstOrNull { !it.premium }
                }
                renderList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askNameAndImport(uri: Uri) {
        val configText = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (!VpnController.isValidConfig(configText)) {
            toast(R.string.import_invalid)
            return
        }

        val defaultName = getString(R.string.imported_default_name)
        val input = EditText(this)
        input.setText(defaultName)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle(R.string.import_name_title)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                val server = Server(
                    name = name,
                    country = getString(R.string.imported_country),
                    premium = false,
                    config = configText.trim(),
                    imported = true,
                )
                imported = imported + server
                ServerRepository.saveImported(this, imported)
                selected = server
                renderList()
                refreshUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onConnectClicked() {
        if (busy) return
        if (VpnController.isUp()) {
            disconnect()
            return
        }
        val server = selected
        if (server == null) {
            toast(R.string.no_server_selected)
            return
        }
        pending = server
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            startTunnel(server)
        }
    }

    private fun startTunnel(server: Server) {
        busy = true
        refreshUi(getString(R.string.status_connecting))
        VpnController.connect(server) { error ->
            runOnUiThread {
                busy = false
                if (error == null) {
                    selected = server
                    prefs.edit().putString(KEY_LAST_ID, server.id).apply()
                    renderList()
                } else {
                    toast(getString(R.string.vpn_error, error.message ?: error.javaClass.simpleName))
                }
                refreshUi()
            }
        }
    }

    private fun disconnect() {
        busy = true
        refreshUi(getString(R.string.status_disconnecting))
        VpnController.disconnect { error ->
            runOnUiThread {
                busy = false
                if (error != null) {
                    toast(getString(R.string.vpn_error, error.message ?: error.javaClass.simpleName))
                }
                refreshUi()
            }
        }
    }

    /** The tunnel dropped by itself. Optionally move on to another free server. */
    private fun handleDrop() {
        refreshUi()
        Toast.makeText(this, R.string.connection_dropped, Toast.LENGTH_LONG).show()
        if (!prefs.getBoolean(KEY_AUTO_SWITCH, false)) return
        val next = all().firstOrNull { !it.premium && it.id != selected?.id } ?: return
        if (VpnService.prepare(this) != null) return
        selected = next
        renderList()
        startTunnel(next)
    }

    private fun refreshUi(message: String? = null) {
        val up = VpnController.isUp()
        statusText.text = when {
            message != null -> message
            up -> getString(R.string.status_connected, VpnController.activeServerName ?: "")
            else -> getString(R.string.status_disconnected)
        }
        connectButton.text = getString(if (up) R.string.btn_disconnect else R.string.btn_connect)
        connectButton.isEnabled = !busy
    }

    private fun renderList() {
        displayed = all().sortedBy { it.country }
        val labels = displayed.map { s ->
            val prefix = when {
                s == selected -> "✓  "
                s.premium -> "🔒  "
                s.imported -> "📁  "
                else -> ""
            }
            "$prefix${s.country} - ${s.name}"
        }
        serverList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun updateSpeed() {
        val totals = VpnController.totals()
        if (totals == null) {
            speedText.text = ""
            lastTime = 0L
            return
        }
        val (rx, tx) = totals
        val now = System.currentTimeMillis()
        if (lastTime != 0L) {
            val seconds = ((now - lastTime) / 1000.0).coerceAtLeast(0.001)
            val down = ((rx - lastRx).coerceAtLeast(0L) / seconds).toLong()
            val up = ((tx - lastTx).coerceAtLeast(0L) / seconds).toLong()
            speedText.text = getString(
                R.string.speed_format,
                formatBytes(down) + "/s",
                formatBytes(up) + "/s",
                formatBytes(rx + tx),
            )
        }
        lastRx = rx
        lastTx = tx
        lastTime = now
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1e3)
        else -> "$bytes B"
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_LAST_ID = "last_server_id"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_AUTO_SWITCH = "auto_switch"
    }
}
