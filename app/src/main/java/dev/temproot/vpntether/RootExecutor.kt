package dev.temproot.vpntether

import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ExecResult(val code: Int, val out: String, val err: String) { val ok get() = code == 0 }

data class RootInfo(
    val ok: Boolean = false,
    val su: String = "-",
    val uid: String = "?",
    val context: String = "?",
    val nsenter: Boolean = false,
)

/** Extrai uid e contexto SELinux da saída de `id`. */
fun parseId(out: String): Pair<String, String> {
    val uid = Regex("uid=(\\d+)\\(([^)]*)\\)").find(out)?.let { "${it.groupValues[1]} (${it.groupValues[2]})" } ?: "?"
    val ctx = Regex("context=(\\S+)").find(out)?.groupValues?.get(1) ?: "?"
    return uid to ctx
}

fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

class RootExecutor {
    @Volatile var suPath: String? = null; private set
    @Volatile var nsenterOk = false; private set

    val available get() = suPath != null

    private val candidates = listOf(
        "/data/adb/ksu/bin/su", "/data/adb/magisk/su", "/data/local/tmp/su",
        "/sbin/su", "/system/bin/su", "/system/xbin/su", "su",
    )

    private suspend fun raw(su: String, cmd: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(su, "-c", cmd).start()
            p.outputStream.close()
            coroutineScope {
                val o = async { p.inputStream.bufferedReader().readText() }
                val e = async { p.errorStream.bufferedReader().readText() }
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    ExecResult(124, "", "timeout (20s)")
                } else ExecResult(p.exitValue(), o.await().trim(), e.await().trim())
            }
        } catch (ex: IOException) {
            ExecResult(127, "", ex.message ?: "falha ao executar")
        }
    }

    /** Procura su (custom primeiro) e valida uid=0; depois testa nsenter. */
    suspend fun detect(custom: String, useNs: Boolean): RootInfo {
        suPath = null; nsenterOk = false
        val list = (listOf(custom.trim()).filter { it.isNotEmpty() } + candidates).distinct()
        for (c in list) {
            LogBus.log("Testando su: $c")
            val r = raw(c, "id")
            if (r.ok && r.out.contains("uid=0")) {
                suPath = c
                val (uid, ctx) = parseId(r.out)
                if (useNs) {
                    val ns = raw(c, "nsenter -t 1 -m -n id")
                    nsenterOk = ns.ok && ns.out.contains("uid=0")
                    LogBus.log(if (nsenterOk) "nsenter -t 1 -m -n: OK" else "nsenter indisponível (${ns.err.ifEmpty { "exit ${ns.code}" }}); rodando sem ele")
                }
                LogBus.log("Root OK via $c -> ${r.out}")
                return RootInfo(true, c, uid, ctx, nsenterOk)
            }
            LogBus.log("  falhou: ${r.err.ifEmpty { r.out.ifEmpty { "exit ${r.code}" } }}")
        }
        LogBus.log("Nenhum su funcional encontrado. Use o campo de caminho customizado ou o Modo Ponte Local.")
        return RootInfo()
    }

    /** Executa um script sh como root, encapsulado em nsenter quando possível. */
    suspend fun run(script: String, ns: Boolean = true, logIt: Boolean = true): ExecResult {
        val su = suPath ?: return ExecResult(127, "", "su não disponível")
        val cmd = if (ns && nsenterOk) "nsenter -t 1 -m -n sh -c ${shq(script)}" else script
        if (logIt) LogBus.log("# $cmd")
        val r = raw(su, cmd)
        if (logIt) {
            if (r.out.isNotEmpty()) LogBus.log(r.out)
            if (r.err.isNotEmpty()) LogBus.log("stderr: ${r.err}")
            LogBus.log("exit=${r.code}")
        }
        return r
    }
}
