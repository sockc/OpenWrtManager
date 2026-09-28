package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.NetworkConfig
import com.openwrtmanager.mobile.model.SafeApplyState
import com.openwrtmanager.mobile.model.WifiNetwork
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun NetworkScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val net by vm.network.collectAsState()
    val wifi by vm.wifi.collectAsState()
    val config by vm.networkConfig.collectAsState()
    val safe by vm.safeApply.collectAsState()

    var dialog by remember { mutableStateOf<String?>(null) }
    var editWifi by remember { mutableStateOf<WifiNetwork?>(null) }

    LaunchedEffect(Unit) { vm.refreshNetwork() }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("网络", style = MaterialTheme.typography.headlineSmall)
                    Text("V0.1.3 · Safe Apply", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = vm::refreshNetwork) { Text("刷新") }
            }
        }

        if (safe.active) {
            item { SafeApplyCard(safe, vm) }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("WAN", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { dialog = "wan" }) { Text("编辑") }
                    }
                    Text(if (net?.wanUp == true) "● 已连接" else "○ 未连接")
                    Text("协议：${net?.wanProto.orEmpty().ifBlank { "--" }}")
                    Text("设备：${net?.wanDevice.orEmpty().ifBlank { "--" }}")
                    Text("IPv4：${net?.wanIpv4.orEmpty().ifBlank { "--" }}")
                    Text("IPv6：${net?.wanIpv6.orEmpty().ifBlank { "--" }}")
                    Text("网关：${net?.wanGateway.orEmpty().ifBlank { "--" }}")
                    Text("DNS：${net?.wanDns?.joinToString(", ").orEmpty().ifBlank { "--" }}")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("LAN", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { dialog = "lan" }) { Text("编辑") }
                    }
                    Text("设备：${net?.lanDevice.orEmpty().ifBlank { "--" }}")
                    Text("IPv4：${net?.lanIpv4.orEmpty().ifBlank { "--" }}")
                    Text("IPv6：${net?.lanIpv6.orEmpty().ifBlank { "--" }}")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    onClick = { dialog = "dhcp" },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("DHCP", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${config?.dhcpStart.orEmpty()} + ${config?.dhcpLimit.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(config?.dhcpLeaseTime.orEmpty().ifBlank { "--" })
                    }
                }

                Card(
                    onClick = { dialog = "dns" },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("DNS", style = MaterialTheme.typography.titleMedium)
                        Text(if (config?.dnsPeer != false) "自动获取" else "自定义")
                        Text(
                            config?.dnsServers?.joinToString(", ").orEmpty().ifBlank { "--" },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item { Text("Wi-Fi", style = MaterialTheme.typography.titleLarge) }

        items(wifi.filter { it.kind == "iface" }, key = { "iface:" + it.section }) { w ->
            val radio = wifi.firstOrNull { it.kind == "radio" && it.section == w.device }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(w.ssid.ifBlank { w.section }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    radio?.band?.ifBlank { null },
                                    radio?.channel?.ifBlank { null }?.let { "信道 $it" },
                                    radio?.htmode?.ifBlank { null }
                                ).joinToString(" · ").ifBlank { w.device },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = {
                            editWifi = w
                            dialog = "wifi"
                        }) { Text("编辑") }
                    }
                    Text("接口：${w.ifname.ifBlank { "--" }}")
                    Text("加密：${w.encryption.ifBlank { "--" }}")
                    Text("客户端：${w.clientCount}")
                    Text(if (w.disabled) "已关闭" else "已启用")
                }
            }
        }
    }

    when (dialog) {
        "lan" -> config?.let {
            LanDialog(
                config = it,
                onDismiss = { dialog = null },
                onSave = { ip, mask ->
                    dialog = null
                    vm.applyLan(ip, mask)
                }
            )
        }

        "dhcp" -> config?.let {
            DhcpDialog(
                config = it,
                onDismiss = { dialog = null },
                onSave = { start, limit, lease ->
                    dialog = null
                    vm.applyDhcp(start, limit, lease)
                }
            )
        }

        "dns" -> config?.let {
            DnsDialog(
                config = it,
                onDismiss = { dialog = null },
                onSave = { peer, servers ->
                    dialog = null
                    vm.applyDns(peer, servers)
                }
            )
        }

        "wan" -> config?.let {
            WanDialog(
                config = it,
                onDismiss = { dialog = null },
                onSave = { proto, user, password, ip, mask, gateway, mtu, wan6 ->
                    dialog = null
                    vm.applyWan(proto, user, password, ip, mask, gateway, mtu, wan6)
                }
            )
        }

        "wifi" -> editWifi?.let { iface ->
            val radio = wifi.firstOrNull { it.kind == "radio" && it.section == iface.device }
            WifiDialog(
                iface = iface,
                radio = radio,
                onDismiss = {
                    dialog = null
                    editWifi = null
                },
                onSave = { enabled, ssid, encryption, password, channel, htmode, country ->
                    dialog = null
                    editWifi = null
                    vm.applyWifi(
                        iface.section,
                        iface.device,
                        enabled,
                        ssid,
                        encryption,
                        password,
                        channel,
                        htmode,
                        country
                    )
                }
            )
        }
    }
}

@Composable
private fun SafeApplyCard(state: SafeApplyState, vm: MainViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("配置等待确认", style = MaterialTheme.typography.titleMedium)
            Text("类型：${state.kind.ifBlank { "network" }}")
            Text("将在 ${state.secondsRemaining} 秒后自动恢复旧配置。")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::confirmSafeApply) { Text("确认保留") }
                OutlinedButton(onClick = vm::rollbackSafeApply) { Text("立即恢复") }
            }
        }
    }
}

@Composable
private fun LanDialog(
    config: NetworkConfig,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var ip by remember { mutableStateOf(config.lanIp) }
    var mask by remember { mutableStateOf(config.lanNetmask.ifBlank { "255.255.255.0" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 LAN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("修改 LAN IP 可能导致当前连接中断。Safe Apply 会在 90 秒内未确认时自动回滚。")
                OutlinedTextField(ip, { ip = it }, label = { Text("LAN IP") })
                OutlinedTextField(mask, { mask = it }, label = { Text("子网掩码") })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onSave(ip.trim(), mask.trim()) }, enabled = ip.isNotBlank() && mask.isNotBlank()) {
                Text("安全应用")
            }
        }
    )
}

@Composable
private fun DhcpDialog(
    config: NetworkConfig,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var start by remember { mutableStateOf(config.dhcpStart) }
    var limit by remember { mutableStateOf(config.dhcpLimit) }
    var lease by remember { mutableStateOf(config.dhcpLeaseTime.ifBlank { "12h" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DHCP 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("起始地址尾号") })
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("地址数量") })
                OutlinedTextField(lease, { lease = it }, label = { Text("租期，例如 12h") })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onSave(start, limit, lease.trim()) }) { Text("安全应用") }
        }
    )
}

@Composable
private fun DnsDialog(
    config: NetworkConfig,
    onDismiss: () -> Unit,
    onSave: (Boolean, List<String>) -> Unit
) {
    var peer by remember { mutableStateOf(config.dnsPeer) }
    var dns by remember { mutableStateOf(config.dnsServers.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DNS 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("自动获取运营商 DNS")
                    Switch(checked = peer, onCheckedChange = { peer = it })
                }
                OutlinedTextField(
                    dns,
                    { dns = it },
                    label = { Text("自定义 DNS，每行一个") },
                    minLines = 3
                )
                Text("关闭自动获取后，路由器将使用这里填写的 DNS。", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                onSave(peer, dns.lines().map { it.trim() }.filter { it.isNotBlank() })
            }) { Text("安全应用") }
        }
    )
}

@Composable
private fun WanDialog(
    config: NetworkConfig,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, String, String, String, String, Boolean) -> Unit
) {
    var proto by remember { mutableStateOf(config.wanProto.ifBlank { "dhcp" }) }
    var user by remember { mutableStateOf(config.wanUsername) }
    var password by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf(config.wanIp) }
    var mask by remember { mutableStateOf(config.wanNetmask) }
    var gateway by remember { mutableStateOf(config.wanGateway) }
    var mtu by remember { mutableStateOf(config.wanMtu) }
    var wan6 by remember { mutableStateOf(config.wan6Enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WAN 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("协议")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = proto == "dhcp", onClick = { proto = "dhcp" }, label = { Text("DHCP") })
                    FilterChip(selected = proto == "pppoe", onClick = { proto = "pppoe" }, label = { Text("PPPoE") })
                    FilterChip(selected = proto == "static", onClick = { proto = "static" }, label = { Text("静态") })
                }

                if (proto == "pppoe") {
                    OutlinedTextField(user, { user = it }, label = { Text("PPPoE 用户名") })
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text(if (config.wanPasswordSet) "新密码（留空保持原密码）" else "PPPoE 密码") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                if (proto == "static") {
                    OutlinedTextField(ip, { ip = it }, label = { Text("IPv4 地址") })
                    OutlinedTextField(mask, { mask = it }, label = { Text("子网掩码") })
                    OutlinedTextField(gateway, { gateway = it }, label = { Text("网关") })
                }

                OutlinedTextField(mtu, { mtu = it.filter(Char::isDigit) }, label = { Text("MTU（留空使用默认）") })

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("启用 WAN6 / IPv6")
                    Switch(checked = wan6, onCheckedChange = { wan6 = it })
                }

                Text("WAN 修改会使用 Safe Apply，90 秒未确认会自动恢复。", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                onSave(
                    proto,
                    user.trim(),
                    password.takeIf { it.isNotBlank() },
                    ip.trim(),
                    mask.trim(),
                    gateway.trim(),
                    mtu.trim(),
                    wan6
                )
            }) { Text("安全应用") }
        }
    )
}

@Composable
private fun WifiDialog(
    iface: WifiNetwork,
    radio: WifiNetwork?,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, String, String?, String, String, String) -> Unit
) {
    var enabled by remember { mutableStateOf(!iface.disabled) }
    var ssid by remember { mutableStateOf(iface.ssid) }
    var encryption by remember { mutableStateOf(iface.encryption.ifBlank { "sae-mixed" }) }
    var password by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf(radio?.channel.orEmpty().ifBlank { "auto" }) }
    var htmode by remember { mutableStateOf(radio?.htmode.orEmpty()) }
    var country by remember { mutableStateOf(radio?.country.orEmpty().ifBlank { "CN" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("启用")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") })
                OutlinedTextField(encryption, { encryption = it }, label = { Text("加密，例如 sae-mixed") })
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("新密码（留空保持原密码）") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(channel, { channel = it }, label = { Text("信道，例如 36 或 auto") })
                OutlinedTextField(htmode, { htmode = it }, label = { Text("带宽模式，例如 HE80") })
                OutlinedTextField(country, { country = it.uppercase().take(2) }, label = { Text("国家/地区") })
                Text("应用后 Wi-Fi 可能短暂断开；未确认将自动恢复。", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        enabled,
                        ssid.trim(),
                        encryption.trim(),
                        password.takeIf { it.isNotBlank() },
                        channel.trim(),
                        htmode.trim(),
                        country.trim()
                    )
                },
                enabled = ssid.isNotBlank()
            ) { Text("安全应用") }
        }
    )
}
