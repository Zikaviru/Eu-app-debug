package dev.temproot.vpntether

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { TopAppBar(title = { Text("TempRoot VPN Tether") }) }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Principal") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Modo Ponte Local") })
            }
            if (tab == 0) MainTab(vm) else BridgeTab(vm)
        }
    }
}

@Composable
private fun MainTab(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsState()
    val root by vm.root.collectAsState()
    val det by vm.detected.collectAsState()
    val busy by vm.busy.collectAsState()
    val checking by vm.checking.collectAsState()
    val notice by vm.notice.collectAsState()
    var dnsEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var suEdit by rememberSaveable { mutableStateOf<String?>(null) }
    val active = prefs.active != null
    val dnsText = dnsEdit ?: prefs.dns

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status do Root", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.checkRoot() }, enabled = !checking) { Text(if (checking) "Verificando…" else "Verificar") }
                }
                Text(if (root.ok) "✔ Acesso root OK" else "✘ Sem root")
                Text("UID: ${root.uid}", fontFamily = FontFamily.Monospace)
                Text("SELinux: ${root.context}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("su: ${root.su}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (!prefs.bridge) Text("nsenter -t 1 -m -n: ${if (root.nsenter) "ativo" else "inativo"}", fontSize = 12.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Interfaces detectadas", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.refresh() }) { Text("Atualizar") }
                }
                Text("VPN: " + (det.vpn?.let { "${it.name}  (${it.ip})" } ?: "não detectada"))
                Text("Hotspot: " + (det.hotspot?.let { "${it.name}  (${it.cidr()})" } ?: "não detectado"))
                if (!det.viaRoot) Text("Detecção sem root (menos precisa).", fontSize = 12.sp)
            }
        }

        Button(
            onClick = { vm.toggle() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        ) {
            Text(
                when { busy -> "Aguarde…"; active -> "DESATIVAR COMPARTILHAMENTO"; else -> "ATIVAR COMPARTILHAMENTO" },
                fontWeight = FontWeight.Bold,
            )
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        prefs.active?.let { Text("Ativo: ${it.vpn} → ${it.hotspot} · DNS ${it.dns} · modo ${it.mode}", fontSize = 12.sp) }

        OutlinedTextField(
            value = dnsText,
            onValueChange = { dnsEdit = it; vm.saveDns(it) },
            label = { Text("DNS para os clientes") },
            isError = !RoutingEngine.IPV4_RE.matches(dnsText.trim()),
            singleLine = true,
            enabled = !active,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = suEdit ?: prefs.suPath,
            onValueChange = { suEdit = it; vm.saveSu(it) },
            label = { Text("Caminho customizado do su") },
            placeholder = { Text("/data/local/tmp/meu_su") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = prefs.useNs, onCheckedChange = { vm.setNs(it); vm.checkRoot() })
            Spacer(Modifier.width(8.dp))
            Text("Usar nsenter -t 1 -m -n (netd global)")
        }
        LogConsole()
    }
}

private fun NetIf.cidr() = "$ip/$prefix"

@Composable
private fun LogConsole() {
    val lines by LogBus.lines.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Console de log (${lines.size})", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                val sc = rememberScrollState()
                LaunchedEffect(lines.size) { sc.scrollTo(sc.maxValue) }
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 320.dp)
                        .background(Color(0xFF101010)).verticalScroll(sc).padding(8.dp)
                ) {
                    SelectionContainer {
                        Text(lines.joinToString("\n"), color = Color(0xFFB7F7B7), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                Row {
                    TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copiar") }
                    TextButton(onClick = { LogBus.clear() }) { Text("Limpar") }
                }
            }
        }
    }
}

@Composable
private fun BridgeTab(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsState()
    val script = remember { vm.bridgeScript() }
    var tokenEdit by rememberSaveable { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Use quando o SELinux impedir o APK de chamar su. Copie o bridge.sh, rode-o como root " +
                "(Termux: su -c \"sh /sdcard/bridge.sh\", ou adb shell → su → sh /data/local/tmp/bridge.sh), " +
                "cole o token exibido abaixo e ative o modo. O script só aceita os comandos PING, ID, UP e DOWN, " +
                "com token e validação de parâmetros.",
            fontSize = 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = prefs.bridge, onCheckedChange = { vm.setBridge(it) })
            Spacer(Modifier.width(8.dp))
            Text("Usar Modo Ponte Local (127.0.0.1:48999)")
        }
        OutlinedTextField(
            value = tokenEdit ?: prefs.token,
            onValueChange = { tokenEdit = it; vm.saveToken(it) },
            label = { Text("Token do bridge") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.testBridge() }) { Text("Testar conexão") }
            OutlinedButton(onClick = { clip.setText(AnnotatedString(script)) }) { Text("Copiar bridge.sh") }
        }
        Box(Modifier.fillMaxWidth().background(Color(0xFF101010)).horizontalScroll(rememberScrollState()).padding(8.dp)) {
            SelectionContainer {
                Text(script, color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false)
            }
        }
    }
}
