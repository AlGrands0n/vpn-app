package com.example.vpnapp

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.Executors

/**
 * Wraps the WireGuard engine.
 * It is a single object per process, so the tunnel state survives Activity restarts.
 */
object VpnController {

    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var activeServerName: String? = null
        private set

    // The engine identifies the tunnel by instance, so this must stay the same object.
    private val tunnel = object : Tunnel {
        override fun getName(): String = "vpnapp"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    fun init(context: Context) {
        if (!::backend.isInitialized) {
            backend = GoBackend(context.applicationContext)
        }
    }

    fun isUp(): Boolean =
        ::backend.isInitialized && backend.getState(tunnel) == Tunnel.State.UP

    /** Starts the tunnel. Only call this after VpnService.prepare() was granted. */
    fun connect(server: Server, onDone: (Throwable?) -> Unit) {
        executor.execute {
            val error: Throwable? = try {
                val config = Config.parse(BufferedReader(StringReader(server.toWireGuardConfig())))
                backend.setState(tunnel, Tunnel.State.UP, config)
                activeServerName = server.name
                null
            } catch (t: Throwable) {
                t
            }
            onDone(error)
        }
    }

    fun disconnect(onDone: (Throwable?) -> Unit) {
        executor.execute {
            val error: Throwable? = try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                activeServerName = null
                null
            } catch (t: Throwable) {
                t
            }
            onDone(error)
        }
    }
}
