package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.DeviceInfo
import com.openwrtmanager.mobile.model.DevicePolicy
import com.openwrtmanager.mobile.model.DeviceTraffic
import com.openwrtmanager.mobile.model.DeviceTrafficCapability
import com.openwrtmanager.mobile.model.QosCapability
import com.openwrtmanager.mobile.ui.MainViewModel
import kotlin.math.roundToInt

@Composable
fun DevicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsState()
    val traffic by vm.deviceTraffic.collectAsState()
    val capability by vm.deviceTrafficCapability.collectAsState()
    val trafficMessage by vm.deviceTrafficMessage.collectAsState()
    val policy by vm.devicePolicy.collectAsState()
    val qosCapability by vm.qosCapability.collectAsState()
    val policyMessage by vm.devicePolicyMessage.collectAsState()

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
                        onDetails = {
                            selected = d
                            vm.loadDevicePolicy(d.mac)
                        }
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
                        onDetails = {
                            selected = d
                            vm.loadDevicePolicy(d.mac)
                        }
                    )
                }
            }
        }
    }

    selected?.let { device ->
        DevicePolicyDialog(
            device = device,
            traffic = trafficByMac[device.mac],
            trafficAvailable = capability.available,
            policy = policy?.takeIf { it.mac.equals(device.mac, ignoreCase = true) },
            qosCapability = qosCapability,
            message = policyMessage,
            vm = vm,
            onDismiss = {
                selected = null
                vm.clearDevicePolicy()
            }
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
                OutlinedButton(onClick = onDetails) { Text("详情/管理") }
                Button(onClick = { vm.setBlocked(device, !device.blocked) }) {
                    Text(if (device.blocked) "恢复联网" else "立即断网")
                }
            }
        }
    }
}

@Composable
private fun DevicePolicyDialog(
    device: DeviceInfo,
    traffic: DeviceTraffic?,
    trafficAvailable: Boolean,
    policy: DevicePolicy?,
    qosCapability: QosCapability,
    message: String,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var alias by remember(device.mac) { mutableStateOf("") }
    var staticIp by remember(device.mac) { mutableStateOf("") }
    var qosEnabled by remember(device.mac) { mutableStateOf(false) }
    var downloadMbps by remember(device.mac) { mutableStateOf("10") }
    var uploadMbps by remember(device.mac) { mutableStateOf("5") }
    var scheduleEnabled by remember(device.mac) { mutableStateOf(false) }
    var weekdays by remember(device.mac) { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var startTime by remember(device.mac) { mutableStateOf("22:00") }
    var endTime by remember(device.mac) { mutableStateOf("07:00") }

    LaunchedEffect(policy) {
        policy?.let {
            alias = it.alias
            staticIp = it.staticIp
            qosEnabled = it.qosEnabled
            if (it.downloadKbps > 0) downloadMbps = formatMbpsInput(it.downloadKbps)
            if (it.uploadKbps > 0) uploadMbps = formatMbpsInput(it.uploadKbps)
            scheduleEnabled = it.schedule.enabled
            weekdays = it.schedule.weekdays.toSet()
            startTime = it.schedule.startTime
            endTime = it.schedule.endTime
        }
    }

    val connectionLabel = when (device.connectionType) {
        "wifi" -> device.band.ifBlank { "Wi-Fi" }
        "ethernet" -> "有线"
        else -> device.connectionType.ifBlank { "未知" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(policy?.alias?.ifBlank { device.hostname } ?: device.hostname) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(if (device.online) "● 当前在线" else "○ 最近发现")
                Text("MAC：${device.mac}", style = MaterialTheme.typography.bodySmall)
                Text("连接：$connectionLabel", style = MaterialTheme.typography.bodySmall)
                if (device.interfaceName.isNotBlank()) {
                    Text("接口：${device.interfaceName}", style = MaterialTheme.typography.bodySmall)
                }
                device.signalDbm?.let {
                    Text("信号：$it dBm", style = MaterialTheme.typography.bodySmall)
                }

                if (trafficAvailable && traffic != null) {
                    Text(
                        "实时 ↓ ${formatRate(traffic.rxBps)}  ↑ ${formatRate(traffic.txBps)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()
                Text("设备名称与固定 IP", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.take(40) },
                    label = { Text("设备名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = staticIp,
                    onValueChange = { staticIp = it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
                    label = { Text("固定 IPv4（留空为自动 DHCP）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.saveDeviceIdentity(device, alias, staticIp) },
                    enabled = policy != null
                ) { Text("保存名称 / 固定 IP") }
                Text(
                    "固定 IP 写入 OpenWrt DHCP host；已有租约通常要设备重新获取 DHCP 后才会切换。",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()
                Text("单设备限速", style = MaterialTheme.typography.titleMedium)
                Text(qosCapability.detail.ifBlank { "正在检测 nft-qos…" }, style = MaterialTheme.typography.bodySmall)

                if (!qosCapability.available) {
                    Button(onClick = vm::installQosBackend) { Text("安装 nft-qos") }
                    Text(
                        "限速后端不会静默安装。安装完成后才允许启用单设备限速。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = qosEnabled, onCheckedChange = { qosEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (qosEnabled) "已启用" else "未启用")
                    }
                    if (qosEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = downloadMbps,
                                onValueChange = { downloadMbps = sanitizeRateInput(it) },
                                label = { Text("下载 Mbps") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = uploadMbps,
                                onValueChange = { uploadMbps = sanitizeRateInput(it) },
                                label = { Text("上传 Mbps") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    val downKbps = mbpsToKbps(downloadMbps)
                    val upKbps = mbpsToKbps(uploadMbps)
                    Button(
                        onClick = { vm.saveDeviceQos(device, qosEnabled, downKbps, upKbps) },
                        enabled = !qosEnabled || (downKbps >= 128 && upKbps >= 128)
                    ) { Text("保存限速") }
                    Text(
                        "使用 nft-qos 的 MAC 限速；本版不修改全局 WAN SQM。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()
                Text("定时断网", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = scheduleEnabled, onCheckedChange = { scheduleEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (scheduleEnabled) "计划已启用" else "计划未启用")
                }

                if (scheduleEnabled) {
                    Text("生效星期", style = MaterialTheme.typography.bodySmall)
                    DayChipRow(
                        days = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四"),
                        selected = weekdays,
                        onToggle = { day ->
                            weekdays = if (day in weekdays) weekdays - day else weekdays + day
                        }
                    )
                    DayChipRow(
                        days = listOf(5 to "五", 6 to "六", 0 to "日"),
                        selected = weekdays,
                        onToggle = { day ->
                            weekdays = if (day in weekdays) weekdays - day else weekdays + day
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startTime,
                            onValueChange = { startTime = sanitizeTimeInput(it) },
                            label = { Text("断网 HH:MM") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endTime,
                            onValueChange = { endTime = sanitizeTimeInput(it) },
                            label = { Text("恢复 HH:MM") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "支持跨午夜，例如 22:00 → 07:00；计划断网和手动断网分开记录，计划结束不会误取消手动断网。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        vm.saveDeviceSchedule(
                            device,
                            scheduleEnabled,
                            weekdays.toList(),
                            startTime,
                            endTime
                        )
                    },
                    enabled = !scheduleEnabled ||
                        (weekdays.isNotEmpty() && validTimeText(startTime) && validTimeText(endTime))
                ) { Text("保存定时断网") }

                if (message.isNotBlank()) {
                    AssistChip(
                        onClick = vm::clearDevicePolicyMessage,
                        label = { Text(message) }
                    )
                }

                HorizontalDivider()
                Text("已观察地址", style = MaterialTheme.typography.titleMedium)
                val addresses = device.addresses.ifEmpty {
                    listOfNotNull(device.ip.takeIf { it.isNotBlank() })
                }
                if (addresses.isEmpty()) {
                    Text("--", style = MaterialTheme.typography.bodySmall)
                } else {
                    addresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun DayChipRow(
    days: List<Pair<Int, String>>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { (value, label) ->
            FilterChip(
                selected = value in selected,
                onClick = { onToggle(value) },
                label = { Text(label) }
            )
        }
    }
}

private fun sanitizeRateInput(value: String): String {
    var dotSeen = false
    return buildString {
        value.forEach { ch ->
            when {
                ch.isDigit() -> append(ch)
                ch == '.' && !dotSeen -> {
                    append(ch)
                    dotSeen = true
                }
            }
        }
    }.take(8)
}

private fun mbpsToKbps(value: String): Int =
    ((value.toDoubleOrNull() ?: 0.0) * 1000.0).roundToInt().coerceIn(0, 1_000_000)

private fun formatMbpsInput(kbps: Int): String {
    val mbps = kbps / 1000.0
    return if (mbps == mbps.toInt().toDouble()) mbps.toInt().toString() else "%.1f".format(mbps)
}

private fun sanitizeTimeInput(value: String): String =
    value.filter { it.isDigit() || it == ':' }.take(5)

private fun validTimeText(value: String): Boolean {
    if (!Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$").matches(value)) return false
    return true
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
