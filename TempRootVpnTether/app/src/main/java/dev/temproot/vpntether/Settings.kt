package dev.temproot.vpntether

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*

private val Context.store by preferencesDataStore(name = "settings")

data class ActiveState(val vpn: String, val hotspot: String, val dns: String, val subnet: String, val mode: String) {
    fun encode() = listOf(vpn, hotspot, dns, subnet, mode).joinToString("|")
    companion object {
        fun decode(s: String): ActiveState? {
            val p = s.split("|")
            return if (p.size == 5) ActiveState(p[0], p[1], p[2], p[3], p[4]) else null
        }
    }
}

data class Prefs(
    val suPath: String = "",
    val dns: String = "1.1.1.1",
    val useNs: Boolean = true,
    val bridge: Boolean = false,
    val token: String = "",
    val active: ActiveState? = null,
)

class Settings(private val ctx: Context) {
    private object K {
        val SU = stringPreferencesKey("su_path")
        val DNS = stringPreferencesKey("dns")
        val NS = booleanPreferencesKey("use_ns")
        val BRIDGE = booleanPreferencesKey("bridge_mode")
        val TOKEN = stringPreferencesKey("bridge_token")
        val ACTIVE = stringPreferencesKey("active_state")
    }

    val flow: Flow<Prefs> = ctx.store.data.map { p ->
        Prefs(
            suPath = p[K.SU] ?: "",
            dns = p[K.DNS] ?: "1.1.1.1",
            useNs = p[K.NS] ?: true,
            bridge = p[K.BRIDGE] ?: false,
            token = p[K.TOKEN] ?: "",
            active = p[K.ACTIVE]?.let { ActiveState.decode(it) },
        )
    }

    suspend fun current(): Prefs = flow.first()
    suspend fun setSu(v: String) { ctx.store.edit { it[K.SU] = v } }
    suspend fun setDns(v: String) { ctx.store.edit { it[K.DNS] = v } }
    suspend fun setNs(v: Boolean) { ctx.store.edit { it[K.NS] = v } }
    suspend fun setBridge(v: Boolean) { ctx.store.edit { it[K.BRIDGE] = v } }
    suspend fun setToken(v: String) { ctx.store.edit { it[K.TOKEN] = v.trim() } }
    suspend fun setActive(a: ActiveState?) {
        ctx.store.edit { if (a == null) it.remove(K.ACTIVE) else it[K.ACTIVE] = a.encode() }
    }
}
