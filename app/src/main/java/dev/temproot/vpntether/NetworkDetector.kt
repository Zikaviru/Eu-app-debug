package dev.temproot.vpntether

import java.net.Inet4Address
import java.net.NetworkInterface

data class NetIf(val name: String, val ip: String, val prefix: Int, val up: Boolean) {
    val subnet: String
        get() {
            val v = ip.split(".").fold(0L) { a, b -> (a shl 8) or b.toLong() }
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val n = v and mask
            return "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}/$prefix"
        }
}

data class Detected(val vpn: NetIf? = null, val hotspot: NetIf? = null, val viaRoot: Boolean = false)

class NetworkDetector(private val root: RootExecutor) {
    private val vpnRe = Regex("^(tun|wg|ppp)[\\w-]*$")
    private val hsOrder = listOf("ap0", "swlan0", "wlan1", "rndis0", "ncm0", "wlan0")

    suspend fun detect(): Detected {
        var viaRoot = false
        var defaults: Set<String> = emptySet()
        var ifs: List<NetIf>? = null
        if (root.available) {
            val r = root.run("ip -o link show; echo ===; ip -o -4 addr show; echo ===; ip route show table all", logIt = false)
            if (r.ok && r.out.contains("===")) {
                val parts = r.out.split("===")
                val up = HashMap<String, Boolean>()
                Regex("^\\d+:\\s+([^:@\\s]+)(?:@\\S+)?:\\s+<([^>]*)>", RegexOption.MULTILINE).findAll(parts[0]).forEach {
                    up[it.groupValues[1]] = it.groupValues[2].split(",").contains("UP")
                }
                val addrs = LinkedHashMap<String, Pair<String, Int>>()
                Regex("^\\d+:\\s+(\\S+)\\s+inet (\\d+\\.\\d+\\.\\d+\\.\\d+)(?: peer \\S+?)?/(\\d+)", RegexOption.MULTILINE)
                    .findAll(parts.getOrElse(1) { "" }).forEach {
                        addrs.putIfAbsent(it.groupValues[1], it.groupValues[2] to it.groupValues[3].toInt())
                    }
                defaults = parts.getOrElse(2) { "" }.lines().filter { it.trimStart().startsWith("default") }
                    .mapNotNull { Regex("dev (\\S+)").find(it)?.groupValues?.get(1) }.toSet()
                ifs = addrs.map { (n, a) -> NetIf(n, a.first, a.second, up[n] ?: false) }
                viaRoot = true
            }
        }
        if (ifs == null) ifs = javaFallback()
        return Detected(pickVpn(ifs), pickHotspot(ifs, defaults, viaRoot), viaRoot)
    }

    private fun javaFallback(): List<NetIf> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { ni ->
            val a = ni.interfaceAddresses.firstOrNull { it.address is Inet4Address } ?: return@mapNotNull null
            NetIf(ni.name, a.address.hostAddress ?: return@mapNotNull null, a.networkPrefixLength.toInt(), runCatching { ni.isUp }.getOrDefault(false))
        }
    }.getOrDefault(emptyList())

    private fun pickVpn(ifs: List<NetIf>): NetIf? =
        ifs.filter { it.up && vpnRe.matches(it.name) }
            .minByOrNull { when { it.name.startsWith("tun") -> 0; it.name.startsWith("wg") -> 1; else -> 2 } }

    private fun isPrivate(ip: String): Boolean {
        val o = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 192 && o[1] == 168 || o[0] == 10 || (o[0] == 172 && o[1] in 16..31)
    }

    private fun pickHotspot(ifs: List<NetIf>, defaults: Set<String>, viaRoot: Boolean): NetIf? =
        ifs.filter { it.name in hsOrder && it.up && isPrivate(it.ip) && it.name !in defaults }
            // Sem acesso root não dá para ler as rotas; wlan0 só é considerado AP se o IP termina em .1
            .filter { viaRoot || it.name != "wlan0" || it.ip.endsWith(".1") }
            .minByOrNull { hsOrder.indexOf(it.name) }
}
