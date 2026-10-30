package com.example.vpnapp

import android.net.VpnService
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var serverList: ListView

    private var servers: List<Server> = emptyList()
    private var selected: Server? = null
    private var pending: Server? = null
    private var busy = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        VpnController.init(this)

        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        serverList = findViewById(R.id.serverList)

        connectButton.setOnClickListener { onConnectClicked() }
        serverList.setOnItemClickListener { _, _, position, _ -> onServerClicked(servers[position]) }

        servers = try {
            ServerRepository.load(this)
        } catch (e: Exception) {
            toast(R.string.servers_load_error)
            emptyList()
        }
        selected = servers.firstOrNull { !it.premium }
        renderList()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        // The VPN can be stopped from outside the app (for example by another VPN), so re-check.
        refreshUi()
    }

    private fun onServerClicked(server: Server) {
        if (server.premium) {
            // Paid servers are only marked here. Real paid access must be enforced by a backend.
            toast(R.string.premium_locked)
            return
        }
        selected = server
        renderList()
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
                refreshUi()
                if (error != null) {
                    toast(getString(R.string.vpn_error, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun disconnect() {
        busy = true
        refreshUi(getString(R.string.status_disconnecting))
        VpnController.disconnect { error ->
            runOnUiThread {
                busy = false
                refreshUi()
                if (error != null) {
                    toast(getString(R.string.vpn_error, error.message ?: error.javaClass.simpleName))
                }
            }
        }
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
        val labels = servers.map { s ->
            val prefix = when {
                s == selected -> "✓  "
                s.premium -> "🔒  "
                else -> ""
            }
            "$prefix${s.country} - ${s.name}"
        }
        serverList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
