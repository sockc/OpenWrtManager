package com.openwrtmanager.mobile.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.DiagnosticCheck
import com.openwrtmanager.mobile.model.HealthItem
import com.openwrtmanager.mobile.model.InterfaceTraffic
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun DiagnosticsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    back: () -> Unit
) {
    val diagnostics by vm.diagnostics.collectAsState()
    val traffic by vm.traffic.collectAsState()
    val health by vm.health.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.runDiagnostics()
        vm.startRealtimeMonitoring()
    }
    DisposableEffect(Unit) {
        onDispose { vm.stopRealtimeMonitoring() }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Column {
                Text("网络诊断", style = MaterialTheme.typography.headlineSmall)
                Text("V0.1.7", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::runDiagnostics) { Text("重新检测") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val report = vm.buildDiagnosticReport()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "OpenWrt Manager Diagnostic Report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享脱敏诊断报告"))
                }
            ) { Text("分享诊断报告") }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("健康检查", style = MaterialTheme.typography.titleLarge)
            }
            items(health) { item -> HealthRow(item) }

            item {
                Spacer(Modifier.height(4.dp))
                Text("连通性", style = MaterialTheme.typography.titleLarge)
            }
            if (diagnostics.checks.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "正在等待诊断结果…",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                items(diagnostics.checks, key = { it.id }) { check ->
                    DiagnosticRow(check)
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("接口监控", style = MaterialTheme.typography.titleLarge)
                Text(
                    "实时速率在首页显示；这里显示累计字节、错误包和丢包。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(traffic.interfaces, key = { it.name }) { iface ->
                InterfaceRow(iface, iface.name == traffic.wanDevice)
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "分享的诊断报告不会包含 Wi-Fi 密码、Token、MAC 地址、SSID 或 IP 地址。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HealthRow(item: HealthItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            val label = when (item.level) {
                "critical" -> "异常"
                "warn" -> "注意"
                else -> "正常"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(label)
            }
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(check: DiagnosticCheck) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(check.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (check.status) {
                        "ok" -> "正常"
                        "warn" -> "跳过/注意"
                        else -> "失败"
                    }
                )
            }
            Text(check.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InterfaceRow(iface: InterfaceTraffic, isWan: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    iface.name + if (isWan) " · WAN" else "",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(if (iface.up) "UP" else "DOWN")
            }
            Text(
                "RX ${formatBytes(iface.rxBytes)} · TX ${formatBytes(iface.txBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "错误 RX ${iface.rxErrors} / TX ${iface.txErrors} · 丢包 RX ${iface.rxDropped} / TX ${iface.txDropped}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
