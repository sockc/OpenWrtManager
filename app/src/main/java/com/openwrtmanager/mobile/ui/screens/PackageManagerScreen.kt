package com.openwrtmanager.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.PackageInfo
import com.openwrtmanager.mobile.ui.MainViewModel

@Composable
fun PackageManagerScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    back: () -> Unit
) {
    val status by vm.packageStatus.collectAsState()
    val packages by vm.packages.collectAsState()
    val message by vm.packageMessage.collectAsState()

    var tab by remember { mutableStateOf("installed") }
    var query by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<Pair<String, PackageInfo>?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshPackageStatus()
        vm.loadInstalledPackages()
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text("返回") }
            Column {
                Text("软件包管理", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (status.manager.isBlank()) "未检测到包管理器" else status.manager.uppercase(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = vm::refreshPackageStatus) { Text("刷新") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("已安装 ${status.installedCount} · 可升级 ${status.upgradableCount}")
                Text(
                    "Overlay 可用 ${formatKb(status.overlayFreeKb)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = vm::updatePackageLists,
                    enabled = status.manager == "opkg"
                ) { Text("更新软件源") }
                Text(
                    "不提供“一键全部升级”，避免基础包或内核模块升级导致路由器异常。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = vm::clearPackageMessage,
                label = { Text(message) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = tab == "installed",
                onClick = {
                    tab = "installed"
                    query = ""
                    vm.loadInstalledPackages()
                },
                label = { Text("已安装") }
            )
            FilterChip(
                selected = tab == "upgradable",
                onClick = {
                    tab = "upgradable"
                    query = ""
                    vm.loadUpgradablePackages()
                },
                label = { Text("可升级") }
            )
            FilterChip(
                selected = tab == "search",
                onClick = {
                    tab = "search"
                    query = ""
                },
                label = { Text("搜索") }
            )
        }

        if (tab == "search") {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("软件包名称，至少 2 个字符") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.searchPackages(query.trim()) },
                    enabled = query.trim().length >= 2
                ) { Text("搜索") }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packages, key = { it.name }) { pkg ->
                PackageCard(
                    pkg = pkg,
                    tab = tab,
                    onInstall = { pendingAction = "install" to pkg },
                    onUpgrade = { pendingAction = "upgrade" to pkg },
                    onRemove = { pendingAction = "remove" to pkg }
                )
            }
        }
    }

    pendingAction?.let { (action, pkg) ->
        val title = when (action) {
            "install" -> "安装 ${pkg.name}"
            "upgrade" -> "升级 ${pkg.name}"
            else -> "卸载 ${pkg.name}"
        }
        val body = when (action) {
            "install" -> "安装软件包可能同时安装依赖，并占用 Overlay 空间。"
            "upgrade" -> "只升级这个软件包。内核和基础系统包不会提供批量升级。"
            else -> "卸载可能影响依赖此包的服务。核心系统包已在路由器端禁止卸载。"
        }

        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = { Text(body) },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    pendingAction = null
                    when (action) {
                        "install" -> vm.installPackage(pkg.name)
                        "upgrade" -> vm.upgradePackage(pkg.name)
                        else -> vm.removePackage(pkg.name)
                    }
                }) { Text("确认") }
            }
        )
    }
}

@Composable
private fun PackageCard(
    pkg: PackageInfo,
    tab: String,
    onInstall: () -> Unit,
    onUpgrade: () -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(pkg.name, style = MaterialTheme.typography.titleMedium)
                    if (pkg.version.isNotBlank()) {
                        Text("当前：${pkg.version}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (pkg.availableVersion.isNotBlank() && pkg.availableVersion != pkg.version) {
                        Text("仓库：${pkg.availableVersion}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (pkg.protected) {
                    AssistChip(onClick = {}, label = { Text("核心包") })
                }
            }

            if (pkg.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(pkg.description.take(240), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            when {
                tab == "upgradable" -> Button(onClick = onUpgrade, enabled = !pkg.protected) { Text("升级") }
                tab == "search" && !pkg.installed -> Button(onClick = onInstall) { Text("安装") }
                tab == "search" && pkg.installed -> Text("已安装", style = MaterialTheme.typography.bodySmall)
                else -> OutlinedButton(onClick = onRemove, enabled = !pkg.protected) { Text("卸载") }
            }
        }
    }
}

private fun formatKb(kb: Long): String = when {
    kb >= 1024 * 1024 -> "%.1f GB".format(kb / 1024.0 / 1024.0)
    kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
    else -> "$kb KB"
}
