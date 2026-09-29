package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.DeviceInfo
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun DevicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsState()
    val online = devices.filter { it.online }
    val recent = devices.filter { !it.online }

    LaunchedEffect(Unit) { vm.refreshDevices() }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("设备", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "在线 ${online.size} · 最近发现 ${recent.size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = vm::refreshDevices) { Text("刷新") }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("当前在线", style = MaterialTheme.typography.titleMedium)
            }
            if (online.isEmpty()) {
                item { EmptyDeviceCard("暂无已验证在线设备") }
            } else {
                items(online, key = { "online:" + it.mac }) { d -> DeviceCard(d, vm) }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("最近发现", style = MaterialTheme.typography.titleMedium)
                Text(
                    "STALE 等邻居缓存不再直接算作在线。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (recent.isEmpty()) {
                item { EmptyDeviceCard("暂无历史邻居") }
            } else {
                items(recent, key = { "recent:" + it.mac }) { d -> DeviceCard(d, vm) }
            }
        }
    }
}

@Composable
private fun EmptyDeviceCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceCard(d: DeviceInfo, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(d.hostname, style = MaterialTheme.typography.titleMedium)
                    Text(d.ip.ifBlank { "--" })
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                d.blocked -> "已断网"
                                d.online -> "在线"
                                else -> "最近发现"
                            }
                        )
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(d.mac, style = MaterialTheme.typography.bodySmall)

            val connection = buildString {
                append(
                    when (d.connectionType) {
                        "wifi" -> d.band.ifBlank { "Wi-Fi" }
                        "ethernet" -> "有线"
                        else -> d.connectionType.ifBlank { "未知" }
                    }
                )
                if (d.interfaceName.isNotBlank()) append(" · ${d.interfaceName}")
                if (d.state.isNotBlank()) append(" · ${d.state}")
            }
            Text(connection, style = MaterialTheme.typography.bodySmall)

            d.signalDbm?.let {
                Text("信号：$it dBm", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.setBlocked(d, !d.blocked) }) {
                Text(if (d.blocked) "恢复联网" else "立即断网")
            }
        }
    }
}
