package dev.temproot.vpntether

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as TetherApp
    val prefs = a.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs())
    val root = MutableStateFlow(RootInfo())
    val detected = MutableStateFlow(Detected())
    val busy = MutableStateFlow(false)
    val checking = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)

    init { checkRoot() }

    fun bridgeScript(): String = a.assets.open("bridge.sh").bufferedReader().readText()

    fun checkRoot() = viewModelScope.launch {
        checking.value = true
        val p = a.settings.current()
        if (p.bridge) {
            val r = a.bridge.send(p.token, "ID")
            val (uid, ctx) = parseId(r.out)
            root.value = RootInfo(r.ok, "bridge 127.0.0.1:48999", uid, ctx)
        } else root.value = a.root.detect(p.suPath, p.useNs)
        checking.value = false
        detected.value = a.detector.detect()
    }

    fun refresh() = viewModelScope.launch { if (!checking.value) detected.value = a.detector.detect() }

    fun toggle() = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        notice.value = null
        val p = a.settings.current()
        if (p.active != null) {
            if (a.engine.disable()) TetherService.stop(getApplication()) else notice.value = "Falha ao desativar; veja o log."
        } else {
            val d = a.detector.detect().also { detected.value = it }
            when {
                d.vpn == null -> notice.value = "Nenhuma VPN ativa (tun/wg/ppp com IP) detectada."
                d.hotspot == null -> notice.value = "Nenhum Hotspot/USB tethering ativo detectado. Ligue-o primeiro."
                else -> if (a.engine.enable(d.vpn, d.hotspot, p.dns))
                    TetherService.start(getApplication(), "${d.vpn.name} → ${d.hotspot.name} · DNS ${p.dns}")
                else notice.value = "Falha ao ativar; veja o log."
            }
        }
        busy.value = false
    }

    fun saveSu(v: String) = viewModelScope.launch { a.settings.setSu(v) }
    fun saveDns(v: String) = viewModelScope.launch { a.settings.setDns(v.trim()) }
    fun setNs(v: Boolean) = viewModelScope.launch { a.settings.setNs(v) }
    fun setBridge(v: Boolean) = viewModelScope.launch { a.settings.setBridge(v); checkRoot() }
    fun saveToken(v: String) = viewModelScope.launch { a.settings.setToken(v) }
    fun testBridge() = viewModelScope.launch {
        val p = a.settings.current()
        a.bridge.send(p.token, "PING")
        checkRoot()
    }
}
