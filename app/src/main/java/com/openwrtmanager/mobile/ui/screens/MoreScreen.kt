package com.openwrtmanager.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.BuildConfig
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun MoreScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf("menu") }
    val latest by vm.latestRelease.collectAsState()
    val updateAvailable by vm.updateAvailable.collectAsState()
    val updateChecking by vm.updateChecking.collectAsState()
    val context = LocalContext.current

    when(page) {
        "logs" -> LogsScreen(vm, modifier) { page = "menu" }
        "terminal" -> TerminalScreen(vm, modifier) { page = "menu" }
        else -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("更多", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium)
                    Text("当前版本：${BuildConfig.VERSION_NAME}")
                    if (latest != null) {
                        Text("最新版本：${latest!!.versionName}")
                        if (updateAvailable) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val url = latest!!.apkUrl.ifBlank { latest!!.htmlUrl }
                                if (url.isNotBlank()) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }) { Text("下载新版本") }
                        } else {
                            Text("已是最新版本", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = vm::checkUpdates, enabled = !updateChecking) {
                        Text(if (updateChecking) "正在检查..." else "检查更新")
                    }
                }
            }

            MoreButton("系统日志", "查看最近系统日志，敏感字段自动脱敏") { page = "logs" }
            MoreButton("SSH 命令终端", "高级操作，直接执行路由器命令") { page = "terminal" }
            MoreButton("重启网络", "重新加载网络接口") { vm.restartNetwork() }
            MoreButton("重启路由器", "设备将暂时离线") { vm.reboot() }
            MoreButton("断开路由器", "返回登录页面") { vm.disconnect() }

            Spacer(Modifier.height(16.dp))
            Text(
                "V0.1.3：Safe Apply / Wi-Fi、LAN、DHCP、DNS、WAN 可编辑 / 自动回滚 / 固定签名升级",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MoreButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogsScreen(vm: MainViewModel, modifier: Modifier, back: () -> Unit) {
    val logs by vm.logs.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshLogs() }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Text("系统日志", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = vm::refreshLogs) { Text("刷新") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索日志") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        val shown = if (query.isBlank()) logs else logs.lineSequence()
            .filter { it.contains(query, ignoreCase = true) }
            .joinToString("\n")

        SelectionContainer {
            Text(
                shown.ifBlank { "暂无日志" },
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TerminalScreen(vm: MainViewModel, modifier: Modifier, back: () -> Unit) {
    val output by vm.terminalOutput.collectAsState()
    var command by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Text("SSH 命令终端", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(48.dp))
        }

        SelectionContainer {
            Text(
                output.ifBlank { "已连接。输入命令后执行。" },
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                command,
                { command = it },
                label = { Text("命令") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.terminal(command); command = "" },
                enabled = command.isNotBlank()
            ) { Text("执行") }
        }
    }
}
