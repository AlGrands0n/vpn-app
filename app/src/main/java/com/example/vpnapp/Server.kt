package com.example.vpnapp

/** One VPN server. Values come from assets/servers.json. */
data class Server(
    val name: String,
    val country: String,
    val premium: Boolean,
    val clientAddress: String,
    val clientPrivateKey: String,
    val dns: String,
    val serverPublicKey: String,
    val endpoint: String,
) {
    /** Builds the WireGuard config text that the tunnel engine expects. */
    fun toWireGuardConfig(): String = """
        [Interface]
        PrivateKey = $clientPrivateKey
        Address = $clientAddress
        DNS = $dns

        [Peer]
        PublicKey = $serverPublicKey
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = $endpoint
        PersistentKeepalive = 25
    """.trimIndent()
}
