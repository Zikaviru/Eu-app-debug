package dev.temproot.vpntether

class RoutingEngine(
    private val root: RootExecutor,
    private val bridge: BridgeClient,
    private val settings: Settings,
) {
    companion object {
        const val TABLE = 99
        const val PREF = 90
        val IFACE_RE = Regex("^[A-Za-z0-9_.-]{1,15}$")
        val IPV4_RE = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    }

    private data class Rule(val label: String, val add: String, val del: String?, val critical: Boolean = true)

    private fun rules(s: ActiveState): List<Rule> {
        val v = s.vpn; val h = s.hotspot; val d = s.dns
        val l = mutableListOf<Rule>()
        l += Rule("ip_forward", "sysctl -w net.ipv4.ip_forward=1", null)
        l += Rule("rota default", "ip route replace default dev $v table $TABLE", "ip route flush table $TABLE")
        if (s.subnet.isNotEmpty())
            l += Rule("rota local", "ip route replace ${s.subnet} dev $h table $TABLE", null, critical = false)
        l += Rule("ip rule", "ip rule add iif $h lookup $TABLE pref $PREF", "ip rule del iif $h lookup $TABLE pref $PREF")
        l += Rule("NAT", "iptables -t nat -I POSTROUTING 1 -o $v -j MASQUERADE", "iptables -t nat -D POSTROUTING -o $v -j MASQUERADE")
        l += Rule("FORWARD hotspot->vpn", "iptables -I FORWARD 1 -i $h -o $v -j ACCEPT", "iptables -D FORWARD -i $h -o $v -j ACCEPT")
        l += Rule("FORWARD vpn->hotspot", "iptables -I FORWARD 1 -i $v -o $h -m state --state RELATED,ESTABLISHED -j ACCEPT",
            "iptables -D FORWARD -i $v -o $h -m state --state RELATED,ESTABLISHED -j ACCEPT")
        l += Rule("MSS clamp", "iptables -t mangle -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu",
            "iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu")
        l += Rule("DNS udp", "iptables -t nat -I PREROUTING 1 -i $h -p udp --dport 53 -j DNAT --to-destination $d:53",
            "iptables -t nat -D PREROUTING -i $h -p udp --dport 53 -j DNAT --to-destination $d:53")
        l += Rule("DNS tcp", "iptables -t nat -I PREROUTING 1 -i $h -p tcp --dport 53 -j DNAT --to-destination $d:53",
            "iptables -t nat -D PREROUTING -i $h -p tcp --dport 53 -j DNAT --to-destination $d:53")
        return l
    }

    suspend fun enable(vpn: NetIf, hs: NetIf, dns: String): Boolean {
        val p = settings.current()
        if (!IFACE_RE.matches(vpn.name) || !IFACE_RE.matches(hs.name)) { LogBus.log("Nome de interface inválido"); return false }
        if (!IPV4_RE.matches(dns)) { LogBus.log("DNS inválido: $dns"); return false }
        if (p.active != null) { LogBus.log("Estado anterior encontrado; limpando antes de ativar."); disable() }

        val st = ActiveState(vpn.name, hs.name, dns, hs.subnet, if (p.bridge) "bridge" else "root")
        settings.setActive(st) // persiste ANTES de aplicar: garante limpeza mesmo se o app morrer
        LogBus.log("Ativando: ${vpn.name} -> ${hs.name} (DNS $dns)")
        val ok = if (p.bridge) {
            bridge.send(p.token, "UP ${st.vpn} ${st.hotspot} ${st.dns}").ok
        } else enableRoot(st)
        if (!ok) settings.setActive(null)
        return ok
    }

    private suspend fun enableRoot(st: ActiveState): Boolean {
        if (!root.available) { LogBus.log("Sem root. Verifique o su ou use o Modo Ponte Local."); return false }
        val done = mutableListOf<Rule>()
        for (r in rules(st)) {
            val res = root.run(r.add)
            if (res.ok) done += r
            else if (r.critical) {
                LogBus.log("Falha em '${r.label}'. Revertendo o que foi aplicado.")
                done.asReversed().forEach { d -> d.del?.let { root.run(it) } }
                return false
            } else LogBus.log("Aviso: '${r.label}' falhou (não crítico).")
        }
        LogBus.log("Compartilhamento ATIVO.")
        return true
    }

    /** Remove exatamente as regras adicionadas (-D / del), em ordem inversa. Não mexe no Wi-Fi. */
    suspend fun disable(): Boolean {
        val p = settings.current()
        val st = p.active ?: return true
        LogBus.log("Desativando...")
        val ok = if (st.mode == "bridge") bridge.send(p.token, "DOWN").ok
        else if (!root.available) { LogBus.log("Sem root para remover regras."); false }
        else {
            rules(st).asReversed().forEach { r -> r.del?.let { cmd ->
                val res = root.run(cmd)
                if (!res.ok) LogBus.log("  (regra '${r.label}' já ausente ou erro; seguindo)")
            } }
            true
        }
        if (ok) { settings.setActive(null); LogBus.log("Compartilhamento DESATIVADO.") }
        return ok
    }
}
