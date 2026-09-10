package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.p1neapplexpress.openflux.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TunnelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Tunnel> {
        val raw = prefs.getString(Constants.PREF_TUNNELS_KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Tunnel>>(raw) }
            .getOrElse { emptyList() }
    }

    fun save(tunnels: List<Tunnel>) {
        prefs.edit {
            putString(Constants.PREF_TUNNELS_KEY, json.encodeToString(tunnels))
        }
    }
}
