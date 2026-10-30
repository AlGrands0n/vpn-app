package com.example.vpnapp

import android.content.Context
import org.json.JSONObject

/** Loads the server list bundled in assets/servers.json. */
object ServerRepository {

    fun load(context: Context): List<Server> {
        val text = context.assets.open("servers.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("servers")
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Server(
                name = o.getString("name"),
                country = o.getString("country"),
                premium = o.optBoolean("premium", false),
                clientAddress = o.getString("clientAddress"),
                clientPrivateKey = o.getString("clientPrivateKey"),
                dns = o.getString("dns"),
                serverPublicKey = o.getString("serverPublicKey"),
                endpoint = o.getString("endpoint"),
            )
        }
    }
}
