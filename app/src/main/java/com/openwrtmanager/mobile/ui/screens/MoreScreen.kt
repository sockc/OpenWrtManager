package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun MoreScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf("menu") }
    when(page) {
        "logs" -> LogsScreen(vm, modifier) { page = "menu" }
        "terminal" -> TerminalScreen(vm, modifier) { page = "menu" }
        else -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("更多", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            MoreButton("系统日志", "查看最近系统日志") { page = "logs" }
            MoreButton("SSH 命令终端", "高级操作，直接执行路由器命令") { page = "terminal" }
            MoreButton("重启网络", "重新加载网络接口") { vm.restartNetwork() }
            MoreButton("重启路由器", "设备将暂时离线") { vm.reboot() }
            MoreButton("断开路由器", "返回登录页面") { vm.disconnect() }
            Spacer(Modifier.height(16.dp))
            Text("V0.1.0：系统状态 / 在线设备 / 一键断网 / 网络与 Wi-Fi 信息 / 服务管理 / 日志 / SSH 命令终端", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MoreButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LogsScreen(vm: MainViewModel, modifier: Modifier, back: () -> Unit) {
    val logs by vm.logs.collectAsState()
    LaunchedEffect(Unit) { vm.refreshLogs() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Text("系统日志", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = vm::refreshLogs) { Text("刷新") }
        }
        SelectionContainer { Text(logs, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
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
        SelectionContainer { Text(output.ifBlank { "已连接。输入命令后执行。" }, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(command, { command = it }, label = { Text("命令") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.terminal(command); command = "" }, enabled = command.isNotBlank()) { Text("执行") }
        }
    }
}
