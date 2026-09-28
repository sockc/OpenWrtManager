package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun AgentInstallScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("需要安装路由端管理组件", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("组件不是 LuCI 插件，不开放额外公网端口。它仅封装系统状态、设备、防火墙和服务操作，APP 仍通过 SSH 连接。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::installAgent) { Text("安装 OpenWrt Manager Agent") }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = vm::disconnect) { Text("断开连接") }
    }
}
