package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun NetworkScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val net by vm.network.collectAsState()
    val wifi by vm.wifi.collectAsState()
    LaunchedEffect(Unit) { vm.refreshNetwork() }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("网络", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = vm::refreshNetwork) { Text("刷新") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("WAN", style = MaterialTheme.typography.titleMedium)
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
                    Text("LAN", style = MaterialTheme.typography.titleMedium)
                    Text("设备：${net?.lanDevice.orEmpty().ifBlank { "--" }}")
                    Text("IPv4：${net?.lanIpv4.orEmpty().ifBlank { "--" }}")
                    Text("IPv6：${net?.lanIpv6.orEmpty().ifBlank { "--" }}")
                }
            }
        }

        item { Text("Wi-Fi", style = MaterialTheme.typography.titleLarge) }

        items(wifi, key = { it.kind + ":" + it.section }) { w ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (w.kind == "iface") w.ssid.ifBlank { w.section } else w.section,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (w.kind == "radio") {
                        Text("频段：${w.band.ifBlank { "--" }}")
                        Text("信道：${w.channel.ifBlank { "自动" }}")
                        Text("带宽/模式：${w.htmode.ifBlank { "--" }}")
                    } else {
                        Text("设备：${w.device.ifBlank { "--" }}")
                        Text("接口：${w.ifname.ifBlank { "--" }}")
                        Text("加密：${w.encryption.ifBlank { "--" }}")
                        Text("客户端：${w.clientCount}")
                    }
                    Text(if (w.disabled) "已关闭" else "已启用")
                }
            }
        }
    }
}
