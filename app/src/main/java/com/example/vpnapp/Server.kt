package com.example.vpnapp

/**
 * One VPN server.
 * `config` is the full WireGuard config text (keys included).
 * `imported` is true for configs the user added from their phone.
 */
data class Server(
    val name: String,
    val country: String,
    val premium: Boolean,
    val config: String,
    val imported: Boolean = false,
) {
    val id: String
        get() = "$country|$name"
}
