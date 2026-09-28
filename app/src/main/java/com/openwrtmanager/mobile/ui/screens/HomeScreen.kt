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
    LaunchedEffect(Unit) { vm.refreshHome(); vm.refreshDevices() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(status?.hostname ?: "OpenWrt", style = MaterialTheme.typography.headlineMedium); Text(status?.model.orEmpty()) }
            TextButton(onClick = vm::refreshHome) { Text("刷新") }
        }
        Spacer(Modifier.height(12.dp))
        status?.let { StatusCards(it) }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("在线设备", style = MaterialTheme.typography.titleMedium)
                Text("${devices.size}", style = MaterialTheme.typography.displaySmall)
                Text("已断网 ${devices.count { it.blocked }} 台")
            }
        }
        Spacer(Modifier.height(12.dp))
        status?.let {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("系统", style = MaterialTheme.typography.titleMedium)
                Text("固件：${it.firmware}")
                Text("内核：${it.kernel}")
                Text("架构：${it.arch}")
                Text("运行：${formatUptime(it.uptimeSeconds)}")
            } }
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
    val usedDisk = if (s.rootTotalKb > 0) ((s.rootTotalKb - s.rootFreeKb) * 100.0 / s.rootTotalKb).roundToInt() else 0
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniCard("负载", "%.2f".format(s.load1), Modifier.weight(1f))
        MiniCard("内存", "$usedMem%", Modifier.weight(1f))
        MiniCard("存储", "$usedDisk%", Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniCard("温度", s.temperatureC?.let { "%.1f°C".format(it) } ?: "--", Modifier.weight(1f))
        MiniCard("可用内存", "${s.memAvailableKb / 1024} MB", Modifier.weight(1f))
    }
}

@Composable
private fun MiniCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp)) { Text(title, style = MaterialTheme.typography.bodySmall); Text(value, style = MaterialTheme.typography.titleLarge) } }
}

private fun formatUptime(seconds: Long): String {
    val d = seconds / 86400; val h = seconds % 86400 / 3600; val m = seconds % 3600 / 60
    return "${d}天 ${h}小时 ${m}分"
}
