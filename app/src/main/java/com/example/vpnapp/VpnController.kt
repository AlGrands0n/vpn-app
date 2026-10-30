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
 * One object per process, so the tunnel state survives Activity restarts.
 */
object VpnController {

    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var activeServerName: String? = null
        private set

    /** True while the user wants the tunnel up. Lets us tell a user disconnect from an unexpected drop. */
    @Volatile
    private var wantUp = false

    /** Called (on the engine's thread) when the tunnel goes down without the user asking. */
    @Volatile
    var onUnexpectedDrop: (() -> Unit)? = null

    // The engine identifies the tunnel by instance, so this must stay the same object.
    private val tunnel = object : Tunnel {
        override fun getName(): String = "vpnapp"

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && wantUp) {
                wantUp = false
                activeServerName = null
                onUnexpectedDrop?.invoke()
            }
        }
    }

    fun init(context: Context) {
        if (!::backend.isInitialized) {
            backend = GoBackend(context.applicationContext)
        }
    }

    fun isUp(): Boolean =
        ::backend.isInitialized && backend.getState(tunnel) == Tunnel.State.UP

    /** Total bytes (received, sent) through the tunnel, or null when it is not running. */
    fun totals(): Pair<Long, Long>? {
        if (!::backend.isInitialized || backend.getState(tunnel) != Tunnel.State.UP) return null
        return try {
            val stats = backend.getStatistics(tunnel)
            Pair(stats.totalRx(), stats.totalTx())
        } catch (t: Throwable) {
            null
        }
    }

    /** Checks that a text is a WireGuard config the engine can read. */
    fun isValidConfig(text: String): Boolean = try {
        Config.parse(BufferedReader(StringReader(text)))
        true
    } catch (t: Throwable) {
        false
    }

    /** Starts the tunnel. Only call this after VpnService.prepare() was granted. */
    fun connect(server: Server, onDone: (Throwable?) -> Unit) {
        executor.execute {
            val error: Throwable? = try {
                val config = Config.parse(BufferedReader(StringReader(server.config)))
                backend.setState(tunnel, Tunnel.State.UP, config)
                activeServerName = server.name
                wantUp = true
                null
            } catch (t: Throwable) {
                t
            }
            onDone(error)
        }
    }

    fun disconnect(onDone: (Throwable?) -> Unit) {
        executor.execute {
            wantUp = false
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
