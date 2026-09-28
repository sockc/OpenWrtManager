package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.RouterProfile
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun RootScreen(vm: MainViewModel) {
    val connected by vm.connected.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (!connected) ConnectionScreen(vm) else ManagerScaffold(vm)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::clearError,
                confirmButton = { TextButton(onClick = vm::clearError) { Text("确定") } },
                title = { Text("操作失败") },
                text = { Text(msg) }
            )
        }
    }
}

@Composable
private fun ConnectionScreen(vm: MainViewModel) {
    val saved = vm.savedProfile
    var host by remember { mutableStateOf(saved?.host ?: "192.168.1.1") }
    var port by remember { mutableStateOf((saved?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(saved?.username ?: "root") }
    var password by remember { mutableStateOf(saved?.password ?: "") }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("OpenWrt Manager", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("通过 SSH 首次连接路由器，管理组件安装后由统一命令接口执行操作。")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(host, { host = it }, label = { Text("路由器地址") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("SSH 端口") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(user, { user = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { vm.connect(RouterProfile(host.trim(), port.toIntOrNull() ?: 22, user.trim(), password)) },
            enabled = host.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("连接路由器") }
        Spacer(Modifier.height(12.dp))
        Text("首次连接采用 TOFU 保存 SSH 主机指纹；后续指纹变化会阻止连接。", style = MaterialTheme.typography.bodySmall)
    }
}
