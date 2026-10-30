package com.example.vpnapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bundled servers (assets/servers.json) plus configs the user imported on this phone. */
object ServerRepository {

    private const val PREFS = "imported_servers"
    private const val KEY = "list"

    fun loadBundled(context: Context): List<Server> {
        val text = context.assets.open("servers.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("servers")
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Server(
                name = o.getString("name"),
                country = o.getString("country"),
                premium = o.optBoolean("premium", false),
                config = buildConfig(
                    clientPrivateKey = o.getString("clientPrivateKey"),
                    clientAddress = o.getString("clientAddress"),
                    dns = o.getString("dns"),
                    serverPublicKey = o.getString("serverPublicKey"),
                    endpoint = o.getString("endpoint"),
                ),
            )
        }
    }

    fun loadImported(context: Context): List<Server> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Server(
                name = o.getString("name"),
                country = o.getString("country"),
                premium = false,
                config = o.getString("config"),
                imported = true,
            )
        }
    }

    fun saveImported(context: Context, list: List<Server>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("name", s.name)
                    .put("country", s.country)
                    .put("config", s.config)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun buildConfig(
        clientPrivateKey: String,
        clientAddress: String,
        dns: String,
        serverPublicKey: String,
        endpoint: String,
    ): String = """
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
