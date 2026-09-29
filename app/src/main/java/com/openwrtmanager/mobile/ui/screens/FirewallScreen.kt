package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.FirewallZone
import com.openwrtmanager.mobile.model.PortForwardRule
import com.openwrtmanager.mobile.model.SafeApplyState
import com.openwrtmanager.mobile.model.TrafficRule
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun FirewallScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    back: () -> Unit
) {
    val firewall by vm.firewall.collectAsState()
    val safe by vm.safeApply.collectAsState()

    var tab by remember { mutableStateOf("redirects") }
    var editing by remember { mutableStateOf<PortForwardRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PortForwardRule?>(null) }

    LaunchedEffect(Unit) { vm.refreshFirewall() }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Column {
                Text("防火墙", style = MaterialTheme.typography.headlineSmall)
                Text("V0.1.5 · Safe Apply", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::refreshFirewall) { Text("刷新") }
        }

        if (safe.active && safe.kind == "firewall") {
            Spacer(Modifier.height(8.dp))
            FirewallSafeApplyCard(safe, vm)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tab == "redirects", onClick = { tab = "redirects" }, label = { Text("端口转发") })
            FilterChip(selected = tab == "rules", onClick = { tab = "rules" }, label = { Text("流量规则") })
            FilterChip(selected = tab == "zones", onClick = { tab = "zones" }, label = { Text("区域") })
        }

        Spacer(Modifier.height(10.dp))

        when (tab) {
            "redirects" -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("端口转发", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { adding = true }) { Text("新增") }
                }
                Spacer(Modifier.height(8.dp))

                if (firewall.redirects.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("暂无端口转发", style = MaterialTheme.typography.titleMedium)
                            Text("新增规则会经过 90 秒 Safe Apply 确认窗口。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(firewall.redirects, key = { it.index }) { rule ->
                            PortForwardCard(
                                rule = rule,
                                onEdit = { editing = rule },
                                onToggle = { vm.updatePortForward(rule.copy(enabled = !rule.enabled)) },
                                onDelete = { deleting = rule }
                            )
                        }
                    }
                }
            }

            "rules" -> {
                Text("流量规则", style = MaterialTheme.typography.titleLarge)
                Text("V0.1.5 支持启用/停用；规则内容先只读。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(firewall.rules, key = { it.index }) { rule ->
                        TrafficRuleCard(rule) { vm.toggleTrafficRule(rule.index, !rule.enabled) }
                    }
                }
            }

            else -> {
                Text("防火墙区域", style = MaterialTheme.typography.titleLarge)
                Text("区域策略本版只读，避免误改 WAN/LAN 基础隔离。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(firewall.zones, key = { it.name }) { zone -> ZoneCard(zone) }
                }
            }
        }
    }

    if (adding) {
        PortForwardDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { name, srcPort, destIp, destPort, proto, enabled ->
                adding = false
                vm.addPortForward(name, srcPort, destIp, destPort, proto, enabled)
            }
        )
    }

    editing?.let { rule ->
        PortForwardDialog(
            initial = rule,
            onDismiss = { editing = null },
            onSave = { name, srcPort, destIp, destPort, proto, enabled ->
                editing = null
                vm.updatePortForward(
                    rule.copy(
                        name = name,
                        srcPort = srcPort,
                        destIp = destIp,
                        destPort = destPort,
                        proto = proto,
                        enabled = enabled
                    )
                )
            }
        )
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除端口转发") },
            text = { Text("确定删除“${rule.name.ifBlank { "未命名规则" }}”？应用后仍有 90 秒自动回滚窗口。") },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    deleting = null
                    vm.deletePortForward(rule.index)
                }) { Text("删除") }
            }
        )
    }
}

@Composable
private fun FirewallSafeApplyCard(state: SafeApplyState, vm: MainViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("防火墙配置等待确认", style = MaterialTheme.typography.titleMedium)
            Text("${state.secondsRemaining} 秒后自动恢复旧配置。")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::confirmSafeApply) { Text("确认保留") }
                OutlinedButton(onClick = vm::rollbackSafeApply) { Text("立即恢复") }
            }
        }
    }
}

@Composable
private fun PortForwardCard(
    rule: PortForwardRule,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rule.name.ifBlank { "未命名规则" }, style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = {}, label = { Text(if (rule.enabled) "已启用" else "已停用") })
            }
            Text(
                "WAN ${rule.srcPort} → ${rule.destIp}:${rule.destPort}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("协议：${protocolLabel(rule.proto)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onToggle) { Text(if (rule.enabled) "停用" else "启用") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun TrafficRuleCard(rule: TrafficRule, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name.ifBlank { "未命名规则" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${rule.src.ifBlank { "*" }} → ${rule.dest.ifBlank { "*" }} · ${rule.target.ifBlank { "--" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }

            val ports = buildList {
                if (rule.srcPort.isNotBlank()) add("源端口 ${rule.srcPort}")
                if (rule.destPort.isNotBlank()) add("目标端口 ${rule.destPort}")
                if (rule.proto.isNotBlank()) add("协议 ${rule.proto}")
            }
            if (ports.isNotEmpty()) {
                Text(ports.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ZoneCard(zone: FirewallZone) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(zone.name.ifBlank { "未命名区域" }, style = MaterialTheme.typography.titleMedium)
            Text(
                "INPUT ${zone.input} · OUTPUT ${zone.output} · FORWARD ${zone.forward}",
                style = MaterialTheme.typography.bodySmall
            )
            if (zone.networks.isNotEmpty()) {
                Text("网络：${zone.networks.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            val extras = buildList {
                if (zone.masquerading) add("Masquerading")
                if (zone.mtuFix) add("MTU Fix")
            }
            if (extras.isNotEmpty()) {
                Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PortForwardDialog(
    initial: PortForwardRule?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var srcPort by remember { mutableStateOf(initial?.srcPort.orEmpty()) }
    var destIp by remember { mutableStateOf(initial?.destIp.orEmpty()) }
    var destPort by remember { mutableStateOf(initial?.destPort.orEmpty()) }
    var proto by remember { mutableStateOf(initial?.proto ?: "tcp") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增端口转发" else "编辑端口转发") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") })
                OutlinedTextField(
                    srcPort,
                    { srcPort = it.filter { ch -> ch.isDigit() || ch == '-' } },
                    label = { Text("外部端口，例如 443") }
                )
                OutlinedTextField(destIp, { destIp = it }, label = { Text("内网设备 IP") })
                OutlinedTextField(
                    destPort,
                    { destPort = it.filter { ch -> ch.isDigit() || ch == '-' } },
                    label = { Text("内部端口") }
                )

                Text("协议")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = proto == "tcp", onClick = { proto = "tcp" }, label = { Text("TCP") })
                    FilterChip(selected = proto == "udp", onClick = { proto = "udp" }, label = { Text("UDP") })
                    FilterChip(selected = proto == "tcpudp", onClick = { proto = "tcpudp" }, label = { Text("TCP+UDP") })
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("启用规则")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Text("保存后会通过 Safe Apply 应用防火墙，90 秒内可恢复。", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        srcPort.trim(),
                        destIp.trim(),
                        destPort.trim(),
                        proto,
                        enabled
                    )
                },
                enabled = srcPort.isNotBlank() && destIp.isNotBlank() && destPort.isNotBlank()
            ) { Text("安全应用") }
        }
    )
}

private fun protocolLabel(proto: String): String = when (proto) {
    "udp" -> "UDP"
    "tcpudp" -> "TCP + UDP"
    else -> "TCP"
}
