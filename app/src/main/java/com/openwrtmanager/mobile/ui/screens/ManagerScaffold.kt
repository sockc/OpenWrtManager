package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.openwrtmanager.mobile.ui.MainViewModel

private enum class Tab(val title: String) { HOME("首页"), DEVICES("设备"), NETWORK("网络"), SERVICES("服务"), MORE("更多") }

@Composable
fun ManagerScaffold(vm: MainViewModel) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val agentInstalled by vm.agentInstalled.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    val icon = when(item) {
                        Tab.HOME -> Icons.Default.Home
                        Tab.DEVICES -> Icons.Default.Devices
                        Tab.NETWORK -> Icons.Default.Language
                        Tab.SERVICES -> Icons.Default.Build
                        Tab.MORE -> Icons.Default.MoreHoriz
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, null) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        if (!agentInstalled) {
            AgentInstallScreen(vm, Modifier.padding(padding))
        } else {
            when(tab) {
                Tab.HOME -> HomeScreen(vm, Modifier.padding(padding))
                Tab.DEVICES -> DevicesScreen(vm, Modifier.padding(padding))
                Tab.NETWORK -> NetworkScreen(vm, Modifier.padding(padding))
                Tab.SERVICES -> ServicesScreen(vm, Modifier.padding(padding))
                Tab.MORE -> MoreScreen(vm, Modifier.padding(padding))
            }
        }
    }
}
