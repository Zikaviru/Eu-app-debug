package dev.temproot.vpntether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Cliente do bridge.sh (127.0.0.1:48999). Só envia verbos fixos: PING, ID, UP, DOWN. */
class BridgeClient {
    suspend fun send(token: String, cmd: String): ExecResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext ExecResult(1, "", "Token do bridge vazio")
        LogBus.log("→ bridge: $cmd")
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 48999), 2000)
                s.soTimeout = 25000
                s.getOutputStream().apply { write("$token $cmd\n".toByteArray()); flush() }
                val resp = s.getInputStream().bufferedReader().readText().trim()
                LogBus.log(resp.ifEmpty { "(sem resposta)" })
                val ok = resp.lines().lastOrNull()?.trim() == "OK"
                ExecResult(if (ok) 0 else 1, resp, if (ok) "" else "bridge retornou erro")
            }
        } catch (e: Exception) {
            LogBus.log("bridge: ${e.message}")
            ExecResult(1, "", e.message ?: "falha de conexão")
        }
    }
}
