package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.ServiceInfo
import com.openwrtmanager.mobile.model.AppServiceInfo
import com.openwrtmanager.mobile.model.ProcessInfo
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun ServicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val services by vm.services.collectAsState()
    val appServices by vm.appServices.collectAsState()
    val processes by vm.processes.collectAsState()
    val serviceLogs by vm.serviceLogs.collectAsState()
    var logService by remember { mutableStateOf<AppServiceInfo?>(null) }
    var section by remember { mutableStateOf("apps") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshServices() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("服务中心", style = MaterialTheme.typography.headlineSmall); Text("应用服务 · 系统服务 · 进程", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = vm::refreshServices) { Text("刷新") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = section == "apps", onClick = { section = "apps"; query = "" }, label = { Text("应用服务") })
            FilterChip(selected = section == "system", onClick = { section = "system"; query = "" }, label = { Text("系统服务") })
            FilterChip(selected = section == "processes", onClick = { section = "processes"; query = "" }, label = { Text("进程") })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query, { query = it }, label = { Text("搜索") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        when (section) {
            "apps" -> AppServiceList(
                appServices.filter { it.displayName.contains(query, true) || it.id.contains(query, true) },
                vm,
                onLogs = {
                    logService = it
                    vm.loadServiceLogs(it.initService)
                }
            )
            "processes" -> ProcessList(processes.filter { it.name.contains(query, true) || it.command.contains(query, true) })
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(services.filter { it.name.contains(query, true) }, key = { it.name }) { s -> ServiceCard(s, vm) }
            }
        }
    }

    logService?.let { service ->
        AlertDialog(
            onDismissRequest = { logService = null },
            title = { Text(service.displayName + " 日志") },
            text = { Text(serviceLogs.ifBlank { "暂无相关日志" }, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { logService = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun ServiceCard(s: ServiceInfo, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(s.name, style = MaterialTheme.typography.titleMedium); if (s.protected) Text("核心服务 · 已启用保护", style = MaterialTheme.typography.bodySmall) }
                Text(if (s.running) "运行中" else "已停止")
            }
            Text(if (s.enabled) "开机启动" else "未设为开机启动", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.serviceAction(s, if (s.running) "stop" else "start") }, enabled = !s.protected || !s.running) { Text(if (s.running) "停止" else "启动") }
                OutlinedButton(onClick = { vm.serviceAction(s, "restart") }, enabled = !s.protected) { Text("重启") }
                TextButton(onClick = { vm.serviceAction(s, if (s.enabled) "disable" else "enable") }, enabled = !s.protected) { Text(if (s.enabled) "取消自启" else "设为自启") }
            }
        }
    }
}


@Composable
private fun AppServiceList(
    items: List<AppServiceInfo>,
    vm: MainViewModel,
    onLogs: (AppServiceInfo) -> Unit
) {
    if (items.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("未识别到应用服务", style = MaterialTheme.typography.titleMedium)
                Text("当前识别常见代理、组网、DNS、容器、文件共享与 DDNS 服务。", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id + ":" + it.initService }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(if (s.running) "正常" else "已停止")
                    }
                    Text(s.detail, style = MaterialTheme.typography.bodySmall)
                    val meta = buildList {
                        s.pid?.let { add("PID " + it) }
                        s.memoryKb?.let { add(formatKb(it)) }
                        if (s.enabled) add("开机自启")
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            vm.appServiceAction(s, if (s.running) "restart" else "start")
                        }) { Text(if (s.running) "重启" else "启动") }
                        TextButton(onClick = {
                            vm.appServiceAction(s, if (s.enabled) "disable" else "enable")
                        }) { Text(if (s.enabled) "取消自启" else "设为自启") }
                        TextButton(onClick = { onLogs(s) }) { Text("日志") }
                    }
                }
            }
        }
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
                        "内存 " + formatKb(p.rssKb) + " · " + "%.2f".format(p.memoryPercent) + "%",
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
    else -> kb.toString() + " KB"
}
