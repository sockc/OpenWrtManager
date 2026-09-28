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
    LaunchedEffect(Unit) { vm.refreshDevices() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("在线设备", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = vm::refreshDevices) { Text("刷新") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { d -> DeviceCard(d, vm) }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceInfo, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.hostname, style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = {}, label = { Text(if (d.blocked) "已断网" else "在线") })
            }
            Text(d.ip)
            Text(d.mac, style = MaterialTheme.typography.bodySmall)
            Text("${d.connectionType} · ${d.interfaceName} · ${d.state}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.setBlocked(d, !d.blocked) }) { Text(if (d.blocked) "恢复联网" else "立即断网") }
        }
    }
}
