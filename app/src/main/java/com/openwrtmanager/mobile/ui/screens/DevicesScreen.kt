package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.DeviceInfo
import com.openwrtmanager.mobile.model.DeviceTraffic
import com.openwrtmanager.mobile.model.DeviceTrafficCapability
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun DevicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsState()
    val traffic by vm.deviceTraffic.collectAsState()
    val capability by vm.deviceTrafficCapability.collectAsState()
    val trafficMessage by vm.deviceTrafficMessage.collectAsState()

    var selected by remember { mutableStateOf<DeviceInfo?>(null) }

    LaunchedEffect(Unit) { vm.refreshDeviceCenter() }
    DisposableEffect(Unit) {
        vm.startDeviceTrafficMonitoring()
        onDispose { vm.stopDeviceTrafficMonitoring() }
    }

    val trafficByMac = traffic.associateBy { it.mac }
    val online = devices
        .filter { it.online }
        .sortedByDescending { trafficByMac[it.mac]?.totalBps ?: 0L }
    val recent = devices.filter { !it.online }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("设备中心", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "在线 ${online.size} · 最近发现 ${recent.size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = vm::refreshDeviceCenter) { Text("刷新") }
        }

        Spacer(Modifier.height(8.dp))
        TrafficBackendCard(
            capability = capability,
            traffic = traffic,
            message = trafficMessage,
            onInstall = vm::installDeviceTrafficBackend,
            onRetry = vm::refreshDeviceCenter,
            onDismissMessage = vm::clearDeviceTrafficMessage
        )

        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("当前在线", style = MaterialTheme.typography.titleMedium)
                if (capability.available) {
                    Text(
                        "按当前实时流量排序 · 累计值为 nlbwmon 当前统计周期",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (online.isEmpty()) {
                item { EmptyDeviceCard("暂无已验证在线设备") }
            } else {
                items(online, key = { "online:" + it.mac }) { d ->
                    DeviceCard(
                        device = d,
                        traffic = trafficByMac[d.mac],
                        trafficAvailable = capability.available,
                        vm = vm,
                        onDetails = { selected = d }
                    )
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("最近发现", style = MaterialTheme.typography.titleMedium)
                Text(
                    "STALE 等邻居缓存不会直接算作在线。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (recent.isEmpty()) {
                item { EmptyDeviceCard("暂无历史邻居") }
            } else {
                items(recent, key = { "recent:" + it.mac }) { d ->
                    DeviceCard(
                        device = d,
                        traffic = trafficByMac[d.mac],
                        trafficAvailable = capability.available,
                        vm = vm,
                        onDetails = { selected = d }
                    )
                }
            }
        }
    }

    selected?.let { device ->
        DeviceDetailDialog(
            device = device,
            traffic = trafficByMac[device.mac],
            trafficAvailable = capability.available,
            onDismiss = { selected = null }
        )
    }
}

@Composable
private fun TrafficBackendCard(
    capability: DeviceTrafficCapability,
    traffic: List<DeviceTraffic>,
    message: String,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("单设备流量", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            capability.available && capability.hasData -> "nlbwmon · 正常采集"
                            capability.available -> "nlbwmon · 已连接，等待流量数据"
                            capability.installed -> "nlbwmon · 已安装但暂不可用"
                            else -> "未安装流量统计组件"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                capability.available -> "可用"
                                capability.installed -> "检查"
                                else -> "未安装"
                            }
                        )
                    }
                )
            }

            if (capability.detail.isNotBlank()) {
                Text(capability.detail, style = MaterialTheme.typography.bodySmall)
            }

            if (capability.available) {
                val rx = traffic.sumOf { it.rxBps }
                val tx = traffic.sumOf { it.txBps }
                val total = traffic.sumOf { it.totalBytes }
                Spacer(Modifier.height(6.dp))
                Text("当前设备合计 ↓ ${formatRate(rx)}  ↑ ${formatRate(tx)}")
                Text("本统计周期累计 ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(8.dp))
                if (!capability.installed) {
                    Button(onClick = onInstall) { Text("安装 nlbwmon") }
                    Text(
                        "安装是显式操作；APP 不会在后台自动安装软件包。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    OutlinedButton(onClick = onRetry) { Text("重新检测") }
                }
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                AssistChip(onClick = onDismissMessage, label = { Text(message) })
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
private fun DeviceCard(
    device: DeviceInfo,
    traffic: DeviceTraffic?,
    trafficAvailable: Boolean,
    vm: MainViewModel,
    onDetails: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(device.hostname, style = MaterialTheme.typography.titleMedium)
                    Text(device.ip.ifBlank { "--" })
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                device.blocked -> "已断网"
                                device.online -> "在线"
                                else -> "最近发现"
                            }
                        )
                    }
                )
            }

            val connection = buildString {
                append(
                    when (device.connectionType) {
                        "wifi" -> device.band.ifBlank { "Wi-Fi" }
                        "ethernet" -> "有线"
                        else -> device.connectionType.ifBlank { "未知" }
                    }
                )
                if (device.interfaceName.isNotBlank()) append(" · ${device.interfaceName}")
                device.signalDbm?.let { append(" · $it dBm") }
            }
            Text(connection, style = MaterialTheme.typography.bodySmall)

            if (trafficAvailable && traffic != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "↓ ${formatRate(traffic.rxBps)}   ↑ ${formatRate(traffic.txBps)}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "统计周期 ${formatBytes(traffic.totalBytes)} · ${traffic.connections} 连接",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetails) { Text("详情") }
                Button(onClick = { vm.setBlocked(device, !device.blocked) }) {
                    Text(if (device.blocked) "恢复联网" else "立即断网")
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailDialog(
    device: DeviceInfo,
    traffic: DeviceTraffic?,
    trafficAvailable: Boolean,
    onDismiss: () -> Unit
) {
    val connectionLabel = when (device.connectionType) {
        "wifi" -> device.band.ifBlank { "Wi-Fi" }
        "ethernet" -> "有线"
        else -> device.connectionType.ifBlank { "未知" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.hostname) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (device.online) "● 当前在线" else "○ 最近发现")
                Text("MAC：${device.mac}")
                Text("连接：$connectionLabel")
                if (device.interfaceName.isNotBlank()) Text("接口：${device.interfaceName}")
                if (device.state.isNotBlank()) Text("邻居状态：${device.state}")
                device.signalDbm?.let { Text("信号：$it dBm") }

                Spacer(Modifier.height(6.dp))
                Text("地址", style = MaterialTheme.typography.titleSmall)
                val addresses = device.addresses.ifEmpty {
                    listOfNotNull(device.ip.takeIf { it.isNotBlank() })
                }
                if (addresses.isEmpty()) {
                    Text("--", style = MaterialTheme.typography.bodySmall)
                } else {
                    addresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                Spacer(Modifier.height(8.dp))
                Text("流量", style = MaterialTheme.typography.titleSmall)
                if (trafficAvailable && traffic != null) {
                    Text("实时下载：${formatRate(traffic.rxBps)}")
                    Text("实时上传：${formatRate(traffic.txBps)}")
                    Text("统计周期下载：${formatBytes(traffic.rxBytes)}")
                    Text("统计周期上传：${formatBytes(traffic.txBytes)}")
                    Text("连接数：${traffic.connections}")
                } else {
                    Text("当前没有可靠的单设备流量数据。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun formatRate(bytesPerSecond: Long): String {
    val b = bytesPerSecond.coerceAtLeast(0)
    return when {
        b >= 1024L * 1024L * 1024L -> "%.2f GB/s".format(b / 1024.0 / 1024.0 / 1024.0)
        b >= 1024L * 1024L -> "%.2f MB/s".format(b / 1024.0 / 1024.0)
        b >= 1024L -> "%.1f KB/s".format(b / 1024.0)
        else -> "$b B/s"
    }
}

private fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b >= 1024L * 1024L * 1024L -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
        b >= 1024L * 1024L -> "%.2f MB".format(b / 1024.0 / 1024.0)
        b >= 1024L -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
