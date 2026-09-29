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
import com.openwrtmanager.mobile.model.AppServiceInfo
import com.openwrtmanager.mobile.model.ProcessInfo
import com.openwrtmanager.mobile.model.ServiceInfo
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun ServicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val services by vm.services.collectAsState()
    val appServices by vm.appServices.collectAsState()
    val processes by vm.processes.collectAsState()
    val serviceLogs by vm.serviceLogs.collectAsState()
    val panel by vm.appPanel.collectAsState()

    var logService by remember { mutableStateOf<AppServiceInfo?>(null) }
    var logLines by remember { mutableIntStateOf(100) }
    var detailService by remember { mutableStateOf<AppServiceInfo?>(null) }
    var confirmStop by remember { mutableStateOf<AppServiceInfo?>(null) }
    var section by remember { mutableStateOf("apps") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshServices() }

    if (panel.open) {
        AppPanelScreen(
            panel = panel,
            modifier = modifier,
            onRetry = vm::retryAppPanel,
            onClose = vm::closeAppPanel
        )
        return
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("应用控制中心", style = MaterialTheme.typography.headlineSmall)
                Text("应用详情 · 管理面板 · 系统服务 · 进程", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::refreshServices) { Text("刷新") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = section == "apps",
                onClick = { section = "apps"; query = "" },
                label = { Text("应用服务") }
            )
            FilterChip(
                selected = section == "system",
                onClick = { section = "system"; query = "" },
                label = { Text("系统服务") }
            )
            FilterChip(
                selected = section == "processes",
                onClick = { section = "processes"; query = "" },
                label = { Text("进程") }
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        when (section) {
            "apps" -> AppServiceList(
                items = appServices.filter {
                    it.displayName.contains(query, true) ||
                        it.id.contains(query, true) ||
                        it.packageName.contains(query, true)
                },
                vm = vm,
                onLogs = {
                    if (it.initService.isNotBlank()) {
                        logLines = 100
                        logService = it
                        vm.loadServiceLogs(it.initService, logLines)
                    }
                },
                onPanel = vm::openAppPanel,
                onDetails = { detailService = it },
                onStop = { confirmStop = it }
            )

            "processes" -> ProcessList(
                processes.filter {
                    it.name.contains(query, true) || it.command.contains(query, true)
                }
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(services.filter { it.name.contains(query, true) }, key = { it.name }) { s ->
                    ServiceCard(s, vm)
                }
            }
        }
    }

    if (panel.opening) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在打开管理面板") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("正在通过 SSH 建立安全隧道…")
                }
            },
            confirmButton = {}
        )
    }

    if (panel.error.isNotBlank()) {
        AlertDialog(
            onDismissRequest = vm::closeAppPanel,
            title = { Text("面板打开失败") },
            text = { Text(panel.error) },
            confirmButton = {
                Row {
                    if (panel.remotePort in 1..65535) {
                        TextButton(onClick = vm::retryAppPanel) { Text("重试") }
                    }
                    TextButton(onClick = vm::closeAppPanel) { Text("关闭") }
                }
            }
        )
    }

    detailService?.let { service ->
        AppDetailDialog(
            service = service,
            onClose = { detailService = null },
            onPanel = {
                detailService = null
                vm.openAppPanel(service)
            },
            onLogs = {
                if (service.initService.isNotBlank()) {
                    logLines = 100
                    logService = service
                    vm.loadServiceLogs(service.initService, logLines)
                }
            },
            onRestart = {
                vm.appServiceAction(service, if (service.running) "restart" else "start")
            },
            onStop = {
                detailService = null
                confirmStop = service
            },
            onToggleEnabled = {
                vm.appServiceAction(service, if (service.enabled) "disable" else "enable")
            },
            onPackage = {
                if (service.packageName.isNotBlank()) {
                    detailService = null
                    vm.openPackageManagerFor(service.packageName)
                }
            }
        )
    }

    confirmStop?.let { service ->
        AlertDialog(
            onDismissRequest = { confirmStop = null },
            title = { Text("停止 ${service.displayName}？") },
            text = {
                Text(
                    "停止代理、DNS 或网络相关应用可能立即影响当前设备联网。重启应用不需要此确认。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmStop = null
                    vm.appServiceAction(service, "stop")
                }) { Text("确认停止") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = null }) { Text("取消") }
            }
        )
    }

    logService?.let { service ->
        AlertDialog(
            onDismissRequest = { logService = null },
            title = { Text(service.displayName + " 日志") },
            text = {
                Column(
                    Modifier.heightIn(min = 240.dp, max = 620.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 300, 1000).forEach { count ->
                            FilterChip(
                                selected = logLines == count,
                                onClick = {
                                    logLines = count
                                    vm.loadServiceLogs(service.initService, count)
                                },
                                label = { Text("$count 行") }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        tonalElevation = 1.dp
                    ) {
                        Text(
                            serviceLogs.ifBlank { "暂无相关日志" },
                            modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        vm.loadServiceLogs(service.initService, logLines)
                    }) { Text("刷新") }
                    TextButton(onClick = { logService = null }) { Text("关闭") }
                }
            }
        )
    }
}

@Composable
private fun ServiceCard(s: ServiceInfo, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    if (s.protected) {
                        Text("核心服务 · 已启用保护", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(if (s.running) "运行中" else "已停止")
            }
            Text(
                if (s.enabled) "开机启动" else "未设为开机启动",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.serviceAction(s, if (s.running) "stop" else "start") },
                    enabled = !s.protected || !s.running
                ) { Text(if (s.running) "停止" else "启动") }

                OutlinedButton(
                    onClick = { vm.serviceAction(s, "restart") },
                    enabled = !s.protected
                ) { Text("重启") }

                TextButton(
                    onClick = {
                        vm.serviceAction(s, if (s.enabled) "disable" else "enable")
                    },
                    enabled = !s.protected
                ) { Text(if (s.enabled) "取消自启" else "设为自启") }
            }
        }
    }
}

@Composable
private fun AppServiceList(
    items: List<AppServiceInfo>,
    vm: MainViewModel,
    onLogs: (AppServiceInfo) -> Unit,
    onPanel: (AppServiceInfo) -> Unit,
    onDetails: (AppServiceInfo) -> Unit,
    onStop: (AppServiceInfo) -> Unit
) {
    if (items.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("未识别到应用或管理面板", style = MaterialTheme.typography.titleMedium)
                Text(
                    "V0.2.1 会扫描真实 LuCI 菜单，并尝试识别带独立 WebUI 的第三方服务。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id + ":" + it.initService + ":" + it.panelPort }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(s.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            when {
                                !s.controllable && s.panelAvailable -> "面板"
                                s.running -> "正常"
                                else -> "已停止"
                            }
                        )
                    }

                    val meta = buildList {
                        if (s.version.isNotBlank()) add("v${s.version}")
                        s.pid?.let { add("PID $it") }
                        s.memoryKb?.let { add(formatKb(it)) }
                        s.uptimeSeconds?.let { add("运行 ${formatUptime(it)}") }
                        if (s.enabled) add("开机自启")
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }

                    if (s.ports.isNotEmpty()) {
                        Text(
                            "监听端口：" + s.ports.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (s.panelAvailable) {
                        Text(
                            when (s.panelKind) {
                                "standalone" -> "独立 WebUI · ${s.panelScheme.uppercase()} :${s.panelPort}"
                                else -> "LuCI 管理面板"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onDetails(s) }) { Text("详情") }

                        if (s.panelAvailable) {
                            Button(onClick = { onPanel(s) }) { Text("管理面板") }
                        }
                    }

                    if (s.controllable) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    vm.appServiceAction(s, if (s.running) "restart" else "start")
                                }
                            ) { Text(if (s.running) "重启" else "启动") }

                            if (s.running) {
                                TextButton(onClick = { onStop(s) }) { Text("停止") }
                            }

                            TextButton(
                                onClick = {
                                    vm.appServiceAction(
                                        s,
                                        if (s.enabled) "disable" else "enable"
                                    )
                                }
                            ) { Text(if (s.enabled) "取消自启" else "设为自启") }

                            TextButton(onClick = { onLogs(s) }) { Text("日志") }
                        }
                    } else if (s.initService.isNotBlank()) {
                        TextButton(onClick = { onLogs(s) }) { Text("日志") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetailDialog(
    service: AppServiceInfo,
    onClose: () -> Unit,
    onPanel: () -> Unit,
    onLogs: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onToggleEnabled: () -> Unit,
    onPackage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(service.displayName) },
        text = {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                DetailRow(
                    "状态",
                    if (service.running) {
                        "运行中"
                    } else if (service.panelAvailable && !service.controllable) {
                        "配置面板"
                    } else {
                        "已停止"
                    }
                )
                if (service.version.isNotBlank()) DetailRow("版本", service.version)
                if (service.packageName.isNotBlank()) {
                    DetailRow("软件包", service.packageName)
                    TextButton(onClick = onPackage) { Text("在软件包管理中查看") }
                }
                service.pid?.let { DetailRow("PID", it.toString()) }
                service.memoryKb?.let { DetailRow("内存", formatKb(it)) }
                service.uptimeSeconds?.let { DetailRow("运行时间", formatUptime(it)) }

                if (service.ports.isNotEmpty()) {
                    DetailRow("监听端口", service.ports.joinToString(", "))
                }

                DetailRow("开机自启", if (service.enabled) "是" else "否")

                if (service.initService.isNotBlank()) {
                    DetailRow("init.d", service.initService)
                }

                if (service.panelAvailable) {
                    DetailRow(
                        "管理面板",
                        if (service.panelKind == "standalone") {
                            "独立 ${service.panelScheme.uppercase()} :${service.panelPort}"
                        } else {
                            "LuCI · ${service.panelPath}"
                        }
                    )
                }

                HorizontalDivider()
                Text(service.detail, style = MaterialTheme.typography.bodySmall)

                if (service.panelAvailable) {
                    Button(onClick = onPanel, modifier = Modifier.fillMaxWidth()) {
                        Text("打开管理面板")
                    }
                }

                if (service.controllable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onRestart) {
                            Text(if (service.running) "重启" else "启动")
                        }
                        if (service.running) {
                            OutlinedButton(onClick = onStop) { Text("停止") }
                        }
                    }
                    TextButton(onClick = onToggleEnabled) {
                        Text(if (service.enabled) "取消开机自启" else "设为开机自启")
                    }
                }

                if (service.initService.isNotBlank()) {
                    TextButton(onClick = onLogs) { Text("查看日志") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProcessList(items: List<ProcessInfo>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.sortedByDescending { it.rssKb }, key = { it.pid }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("PID " + p.pid, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "内存 " + formatKb(p.rssKb) + " · " +
                            "%.2f".format(p.memoryPercent) + "%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (p.user.isNotBlank()) {
                        Text("用户：" + p.user, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (p.protected) "系统关键进程 · 仅查看" else "当前版本仅查看",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatKb(kb: Long): String = when {
    kb >= 1024 * 1024 -> "%.1f GB".format(kb / 1024.0 / 1024.0)
    kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
    else -> "$kb KB"
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分"
        else -> "${minutes}分"
    }
}
