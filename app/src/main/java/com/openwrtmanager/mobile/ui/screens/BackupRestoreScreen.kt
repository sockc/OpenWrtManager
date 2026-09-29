package com.openwrtmanager.mobile.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.BackupInfo
import com.openwrtmanager.mobile.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupRestoreScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    back: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message by vm.backupMessage.collectAsState()

    var pendingExport by remember { mutableStateOf<Pair<BackupInfo, ByteArray>?>(null) }
    var pendingRestore by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) {
            scope.launch(Dispatchers.IO) {
                writeBytes(context, uri, item.second)
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取备份文件")
                    require(bytes.isNotEmpty()) { "备份文件为空" }
                    require(bytes.size <= 32 * 1024 * 1024) { "备份文件超过 32 MB" }
                    val name = uri.lastPathSegment ?: "backup.tar.gz"
                    name to bytes
                }.onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        pendingRestore = result
                        restoreConfirm = true
                    }
                }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Column {
                Text("备份与恢复", style = MaterialTheme.typography.headlineSmall)
                Text("OpenWrt sysupgrade 配置备份", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(48.dp))
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("配置备份", style = MaterialTheme.typography.titleMedium)
                Text(
                    "生成 OpenWrt 标准备份并保存到手机。支持时会把当前已安装软件包列表一起写入备份。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    vm.createConfigBackup { info, bytes ->
                        pendingExport = info to bytes
                        createDocument.launch(info.filename)
                    }
                }) { Text("生成并保存备份") }
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("恢复配置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "选择由 OpenWrt sysupgrade 生成的 .tar.gz 配置备份。恢复会覆盖现有配置，完成后需要重启路由器。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    openDocument.launch(
                        arrayOf(
                            "application/gzip",
                            "application/x-gzip",
                            "application/octet-stream"
                        )
                    )
                }) { Text("选择备份文件") }
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("恢复前注意", style = MaterialTheme.typography.titleMedium)
                Text("• 建议只恢复同一台路由器或兼容固件生成的备份。")
                Text("• 恢复可能改变 LAN IP、Wi-Fi、SSH、DNS 和防火墙配置。")
                Text("• APP 不会自动恢复固件本体；这里只处理配置备份。")
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            AssistChip(
                onClick = vm::clearBackupMessage,
                label = { Text(message) }
            )
            if (message.contains("重启")) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::reboot) { Text("立即重启路由器") }
            }
        }
    }

    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = {
                restoreConfirm = false
                pendingRestore = null
            },
            title = { Text("确认恢复配置") },
            text = {
                Text(
                    "即将恢复“${pendingRestore?.first ?: "backup.tar.gz"}”。" +
                        "现有 OpenWrt 配置会被覆盖，恢复成功后需要重启。"
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    restoreConfirm = false
                    pendingRestore = null
                }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    val bytes = pendingRestore?.second
                    restoreConfirm = false
                    pendingRestore = null
                    if (bytes != null) vm.restoreConfigBackup(bytes)
                }) { Text("确认恢复") }
            }
        )
    }
}

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        ?: error("无法写入备份文件")
}
