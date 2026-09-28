package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.SystemStatus
import com.openwrtmanager.mobile.ui.MainViewModel
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val status by vm.status.collectAsState()
    val devices by vm.devices.collectAsState()
    val network by vm.network.collectAsState()
    val wifi by vm.wifi.collectAsState()
    LaunchedEffect(Unit) { vm.refreshHome() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(status?.hostname ?: "OpenWrt", style = MaterialTheme.typography.headlineMedium)
                Text(status?.model.orEmpty())
                Text("● 在线", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::refreshHome) { Text("刷新") }
        }

        Spacer(Modifier.height(12.dp))
        status?.let { StatusCards(it) }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("WAN", style = MaterialTheme.typography.titleMedium)
                Text(if (network?.wanUp == true) "已连接 · ${network?.wanProto.orEmpty().uppercase()}" else "未连接")
                Text("IPv4：${network?.wanIpv4.orEmpty().ifBlank { "--" }}")
                val ipv6 = network?.wanIpv6.orEmpty()
                if (ipv6.isNotBlank()) Text("IPv6：$ipv6", style = MaterialTheme.typography.bodySmall)
                val gateway = network?.wanGateway.orEmpty()
                if (gateway.isNotBlank()) Text("网关：$gateway", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("在线设备", style = MaterialTheme.typography.titleMedium)
                    Text("${devices.size}", style = MaterialTheme.typography.displaySmall)
                    Text("已断网 ${devices.count { it.blocked }} 台", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Wi-Fi", style = MaterialTheme.typography.titleMedium)
                    val radios = wifi.filter { it.kind == "radio" }
                    Text("${radios.count { !it.disabled }} / ${radios.size}", style = MaterialTheme.typography.displaySmall)
                    Text("无线电已启用", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        status?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("系统", style = MaterialTheme.typography.titleMedium)
                    Text("固件：${it.firmware}")
                    Text("内核：${it.kernel}")
                    Text("架构：${it.arch}")
                    Text("运行：${formatUptime(it.uptimeSeconds)}")
                    network?.lanIpv4?.takeIf { ip -> ip.isNotBlank() }?.let { ip -> Text("LAN：$ip") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::restartNetwork, modifier = Modifier.weight(1f)) { Text("重启网络") }
            OutlinedButton(onClick = vm::disconnect, modifier = Modifier.weight(1f)) { Text("断开") }
        }
    }
}

@Composable
private fun StatusCards(s: SystemStatus) {
    val usedMem = if (s.memTotalKb > 0) ((s.memTotalKb - s.memAvailableKb) * 100.0 / s.memTotalKb).roundToInt() else 0
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniCard("CPU", "${s.cpuPercent}%", Modifier.weight(1f))
        MiniCard("内存", "$usedMem%", Modifier.weight(1f))
        MiniCard("温度", s.temperatureC?.let { "%.1f°C".format(it) } ?: "--", Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniCard("负载", "%.2f".format(s.load1), Modifier.weight(1f))
        MiniCard("可用内存", "${s.memAvailableKb / 1024} MB", Modifier.weight(1f))
    }
}

@Composable
private fun MiniCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = seconds % 86400 / 3600
    val m = seconds % 3600 / 60
    return "${d}天 ${h}小时 ${m}分"
}
