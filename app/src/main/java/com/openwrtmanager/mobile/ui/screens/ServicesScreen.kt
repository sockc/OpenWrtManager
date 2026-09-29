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
    var section by remember { mutableStateOf("apps") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshServices() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("服务中心", style = MaterialTheme.typography.headlineSmall); Text("应用服务 · 系统服务 · 进程", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = vm::refreshServices) { Text("刷新") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("搜索服务") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(services.filter { it.name.contains(query, true) }, key = { it.name }) { s -> ServiceCard(s, vm) }
        }
    }
}

@Composable
private fun ServiceCard(s: ServiceInfo, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                Text(if (s.running) "运行中" else "已停止")
            }
            Text(if (s.enabled) "开机启动" else "未设为开机启动", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.serviceAction(s, if (s.running) "stop" else "start") }) { Text(if (s.running) "停止" else "启动") }
                OutlinedButton(onClick = { vm.serviceAction(s, "restart") }) { Text("重启") }
                TextButton(onClick = { vm.serviceAction(s, if (s.enabled) "disable" else "enable") }) { Text(if (s.enabled) "取消自启" else "设为自启") }
            }
        }
    }
}
