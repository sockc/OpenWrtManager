package com.openwrtmanager.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmanager.mobile.agent.AgentClient
import com.openwrtmanager.mobile.data.SecureStore
import com.openwrtmanager.mobile.model.*
import com.openwrtmanager.mobile.ssh.SshManager
import com.openwrtmanager.mobile.update.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val ssh = SshManager(store)
    private val agent = AgentClient(app, ssh)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint: StateFlow<String?> = _fingerprint.asStateFlow()
    private val _agentInstalled = MutableStateFlow(false)
    val agentInstalled: StateFlow<Boolean> = _agentInstalled.asStateFlow()

    private val _status = MutableStateFlow<SystemStatus?>(null)
    val status: StateFlow<SystemStatus?> = _status.asStateFlow()
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()
    private val _network = MutableStateFlow<NetworkSummary?>(null)
    val network: StateFlow<NetworkSummary?> = _network.asStateFlow()
    private val _wifi = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val wifi: StateFlow<List<WifiNetwork>> = _wifi.asStateFlow()
    private val _networkConfig = MutableStateFlow<NetworkConfig?>(null)
    val networkConfig: StateFlow<NetworkConfig?> = _networkConfig.asStateFlow()
    private val _safeApply = MutableStateFlow(SafeApplyState())
    val safeApply: StateFlow<SafeApplyState> = _safeApply.asStateFlow()
    private val _firewall = MutableStateFlow(FirewallSnapshot())
    val firewall: StateFlow<FirewallSnapshot> = _firewall.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficSnapshot())
    val traffic: StateFlow<TrafficSnapshot> = _traffic.asStateFlow()
    private val _wanRxHistory = MutableStateFlow<List<Long>>(emptyList())
    val wanRxHistory: StateFlow<List<Long>> = _wanRxHistory.asStateFlow()
    private val _wanTxHistory = MutableStateFlow<List<Long>>(emptyList())
    val wanTxHistory: StateFlow<List<Long>> = _wanTxHistory.asStateFlow()
    private val _diagnostics = MutableStateFlow(DiagnosticSummary())
    val diagnostics: StateFlow<DiagnosticSummary> = _diagnostics.asStateFlow()
    private val _health = MutableStateFlow<List<HealthItem>>(emptyList())
    val health: StateFlow<List<HealthItem>> = _health.asStateFlow()
    private var realtimeJob: Job? = null
    private var previousTraffic: TrafficSnapshot? = null

    private val _packageStatus = MutableStateFlow(PackageManagerStatus())
    val packageStatus: StateFlow<PackageManagerStatus> = _packageStatus.asStateFlow()
    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()
    private val _packageMessage = MutableStateFlow("")
    val packageMessage: StateFlow<String> = _packageMessage.asStateFlow()
    private val _backupMessage = MutableStateFlow("")
    val backupMessage: StateFlow<String> = _backupMessage.asStateFlow()

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()
    private val _appServices = MutableStateFlow<List<AppServiceInfo>>(emptyList())
    val appServices: StateFlow<List<AppServiceInfo>> = _appServices.asStateFlow()
    private val _processes = MutableStateFlow<List<ProcessInfo>>(emptyList())
    val processes: StateFlow<List<ProcessInfo>> = _processes.asStateFlow()
    private val _serviceLogs = MutableStateFlow("")
    val serviceLogs: StateFlow<String> = _serviceLogs.asStateFlow()
    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs.asStateFlow()
    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val _latestRelease = MutableStateFlow<ReleaseInfo?>(null)
    val latestRelease: StateFlow<ReleaseInfo?> = _latestRelease.asStateFlow()
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()
    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    val savedProfile: RouterProfile? get() = store.loadProfile()

    init {
        checkUpdates()
    }

    fun connect(profile: RouterProfile) = viewModelScope.launch {
        task {
            connectInternal(profile)
            refreshCoreInternal()
            runCatching { _networkConfig.value = agent.config() }
            runCatching { _safeApply.value = agent.safeStatus() }
        }
    }

    private suspend fun connectInternal(profile: RouterProfile) {
        val fp = ssh.connect(profile).getOrThrow()
        store.saveProfile(profile)
        _fingerprint.value = fp
        _connected.value = true
        val installedVersion = agent.agentVersion()
        _agentInstalled.value = installedVersion != null
        if (_agentInstalled.value && installedVersion != AgentClient.BUNDLED_AGENT_VERSION) {
            agent.installAgent()
        }
    }

    fun disconnect() {
        ssh.disconnect()
        _connected.value = false
        _status.value = null
        _network.value = null
        _wifi.value = emptyList()
        _devices.value = emptyList()
        _networkConfig.value = null
        _safeApply.value = SafeApplyState()
        stopRealtimeMonitoring()
        _traffic.value = TrafficSnapshot()
        _wanRxHistory.value = emptyList()
        _wanTxHistory.value = emptyList()
        _diagnostics.value = DiagnosticSummary()
        _health.value = emptyList()
        previousTraffic = null
    }

    fun installAgent() = viewModelScope.launch {
        task {
            agent.installAgent()
            _agentInstalled.value = agent.agentVersion() != null
            refreshCoreInternal()
            _networkConfig.value = agent.config()
        }
    }

    fun refreshHome() = viewModelScope.launch {
        task {
            _status.value = agent.status()
            _network.value = agent.network()
            _wifi.value = agent.wifi()
            _devices.value = agent.devices()
            runCatching { updateTrafficSample() }
            recomputeHealth()
        }
    }


    fun startRealtimeMonitoring() {
        if (realtimeJob?.isActive == true) return
        realtimeJob = viewModelScope.launch {
            var cycle = 0
            while (_connected.value) {
                runCatching { updateTrafficSample() }
                if (cycle % 2 == 0) {
                    runCatching { _status.value = agent.status() }
                    runCatching { _network.value = agent.network() }
                    recomputeHealth()
                }
                cycle++
                delay(3000)
            }
        }
    }

    fun stopRealtimeMonitoring() {
        realtimeJob?.cancel()
        realtimeJob = null
    }

    private suspend fun updateTrafficSample() {
        val current = agent.traffic()
        val previous = previousTraffic
        val dt = if (previous != null) current.timestampMs - previous.timestampMs else 0L

        var rxBps = 0L
        var txBps = 0L
        if (previous != null && dt > 0 && current.wanDevice.isNotBlank()) {
            val nowWan = current.interfaces.firstOrNull { it.name == current.wanDevice }
            val oldWan = previous.interfaces.firstOrNull { it.name == current.wanDevice }
            if (nowWan != null && oldWan != null) {
                val rxDelta = (nowWan.rxBytes - oldWan.rxBytes).coerceAtLeast(0)
                val txDelta = (nowWan.txBytes - oldWan.txBytes).coerceAtLeast(0)
                rxBps = rxDelta * 1000L / dt
                txBps = txDelta * 1000L / dt
            }
        }

        val rated = current.copy(wanRxBps = rxBps, wanTxBps = txBps)
        previousTraffic = current
        _traffic.value = rated
        _wanRxHistory.value = (_wanRxHistory.value + rxBps).takeLast(20)
        _wanTxHistory.value = (_wanTxHistory.value + txBps).takeLast(20)
    }

    fun runDiagnostics() = viewModelScope.launch {
        task {
            _diagnostics.value = agent.diagnostics()
            recomputeHealth()
        }
    }

    private fun recomputeHealth() {
        val items = mutableListOf<HealthItem>()
        val s = _status.value
        val n = _network.value

        if (n != null && !n.wanUp) {
            items += HealthItem("critical", "WAN 未连接", "检查上联、PPPoE 或 DHCP 状态")
        }

        if (s != null && s.memTotalKb > 0) {
            val availablePercent = s.memAvailableKb * 100 / s.memTotalKb
            if (availablePercent < 10) {
                items += HealthItem("warn", "可用内存偏低", "当前可用约 ${s.memAvailableKb / 1024} MB")
            }
        }

        if (s != null && s.rootFreeKb in 1 until 10_240) {
            items += HealthItem("warn", "存储空间偏低", "Overlay/根目录可用不足 10 MB")
        }

        val temp = s?.temperatureC
        if (temp != null && temp >= 80.0) {
            items += HealthItem("warn", "温度较高", "当前约 %.1f°C".format(temp))
        }

        if (_safeApply.value.active) {
            items += HealthItem("warn", "配置等待确认", "Safe Apply 尚未确认")
        }

        _diagnostics.value.checks.filter { it.status == "fail" }.forEach {
            items += HealthItem("critical", it.title, it.detail)
        }

        if (items.isEmpty()) {
            items += HealthItem("ok", "状态正常", "未发现明显异常")
        }
        _health.value = items
    }

    fun buildDiagnosticReport(): String {
        val s = _status.value
        val n = _network.value
        val t = _traffic.value
        val online = _devices.value.count { it.online }

        return buildString {
            appendLine("OpenWrt Manager Diagnostic Report")
            appendLine("App: 0.1.7")
            appendLine()
            appendLine("[System]")
            appendLine("Model: ${s?.model.orEmpty()}")
            appendLine("Firmware: ${s?.firmware.orEmpty()}")
            appendLine("Kernel: ${s?.kernel.orEmpty()}")
            appendLine("Arch: ${s?.arch.orEmpty()}")
            appendLine("UptimeSeconds: ${s?.uptimeSeconds ?: 0}")
            appendLine("CPU: ${s?.cpuPercent ?: 0}%")
            appendLine("MemAvailableKB: ${s?.memAvailableKb ?: 0}")
            appendLine("RootFreeKB: ${s?.rootFreeKb ?: 0}")
            appendLine("TemperatureC: ${s?.temperatureC ?: "n/a"}")
            appendLine()
            appendLine("[Network]")
            appendLine("WANUp: ${n?.wanUp ?: false}")
            appendLine("WANProto: ${n?.wanProto.orEmpty()}")
            appendLine("WANDevice: ${t.wanDevice}")
            appendLine("WANRxBps: ${t.wanRxBps}")
            appendLine("WANTxBps: ${t.wanTxBps}")
            appendLine("OnlineDevices: $online")
            appendLine()
            appendLine("[Interfaces]")
            t.interfaces.forEach { i ->
                appendLine("${i.name}: up=${i.up}, rx=${i.rxBytes}, tx=${i.txBytes}, rxErr=${i.rxErrors}, txErr=${i.txErrors}, rxDrop=${i.rxDropped}, txDrop=${i.txDropped}")
            }
            appendLine()
            appendLine("[Health]")
            _health.value.forEach { appendLine("${it.level}: ${it.title} - ${it.detail}") }
            appendLine()
            appendLine("[Diagnostics]")
            _diagnostics.value.checks.forEach { appendLine("${it.status}: ${it.title} - ${it.detail}") }
            appendLine()
            appendLine("Sensitive fields such as passwords, tokens, MAC addresses, SSIDs and IP addresses are intentionally omitted.")
        }
    }

    fun refreshDevices() = viewModelScope.launch { task { _devices.value = agent.devices() } }

    fun refreshNetwork() = viewModelScope.launch {
        task {
            _network.value = agent.network()
            _wifi.value = agent.wifi()
            _networkConfig.value = agent.config()
            _safeApply.value = runCatching { agent.safeStatus() }.getOrDefault(SafeApplyState())
        }
    }

    fun refreshFirewall() = viewModelScope.launch {
        task { _firewall.value = agent.firewallSnapshot() }
    }

    fun addPortForward(
        name: String,
        srcPort: String,
        destIp: String,
        destPort: String,
        proto: String,
        enabled: Boolean
    ) = firewallChange {
        agent.addPortForward(name, srcPort, destIp, destPort, proto, enabled)
    }

    fun updatePortForward(rule: PortForwardRule) = firewallChange {
        agent.updatePortForward(rule)
    }

    fun deletePortForward(index: Int) = firewallChange {
        agent.deletePortForward(index)
    }

    fun toggleTrafficRule(index: Int, enabled: Boolean) = firewallChange {
        agent.toggleTrafficRule(index, enabled)
    }

    private fun firewallChange(setter: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        try {
            val state = agent.safeBegin("firewall", 90)
            _safeApply.value = state
            setter()
            agent.applyConfig("firewall")
            watchSafeApply(state)
            delay(1200)
            _firewall.value = agent.firewallSnapshot()
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        } finally {
            _busy.value = false
        }
    }


    fun refreshPackageStatus() = viewModelScope.launch {
        task { _packageStatus.value = agent.packageStatus() }
    }

    fun loadInstalledPackages() = viewModelScope.launch {
        task {
            _packages.value = agent.installedPackages()
            _packageStatus.value = agent.packageStatus()
        }
    }

    fun loadUpgradablePackages() = viewModelScope.launch {
        task {
            _packages.value = agent.upgradablePackages()
            _packageStatus.value = agent.packageStatus()
        }
    }

    fun searchPackages(query: String) = viewModelScope.launch {
        task { _packages.value = agent.searchPackages(query) }
    }

    fun updatePackageLists() = viewModelScope.launch {
        task {
            agent.updatePackageLists()
            _packageMessage.value = "软件源更新成功"
            _packageStatus.value = agent.packageStatus()
        }
    }

    fun installPackage(name: String) = viewModelScope.launch {
        task {
            agent.installPackage(name)
            _packageMessage.value = "已安装 $name"
            _packageStatus.value = agent.packageStatus()
            _packages.value = agent.installedPackages()
        }
    }

    fun upgradePackage(name: String) = viewModelScope.launch {
        task {
            agent.upgradePackage(name)
            _packageMessage.value = "已升级 $name"
            _packageStatus.value = agent.packageStatus()
            _packages.value = agent.upgradablePackages()
        }
    }

    fun removePackage(name: String) = viewModelScope.launch {
        task {
            agent.removePackage(name)
            _packageMessage.value = "已卸载 $name"
            _packageStatus.value = agent.packageStatus()
            _packages.value = agent.installedPackages()
        }
    }

    fun clearPackageMessage() { _packageMessage.value = "" }
    fun clearBackupMessage() { _backupMessage.value = "" }

    fun createConfigBackup(onReady: (BackupInfo, ByteArray) -> Unit) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        try {
            val result = agent.createBackup()
            _backupMessage.value = "备份已生成"
            onReady(result.first, result.second)
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        } finally {
            _busy.value = false
        }
    }

    fun restoreConfigBackup(bytes: ByteArray) = viewModelScope.launch {
        task {
            agent.restoreBackup(bytes)
            _backupMessage.value = "配置已恢复，重启路由器后生效"
        }
    }

    fun refreshServices() = viewModelScope.launch {
        task {
            _appServices.value = agent.appServices()
            _services.value = agent.services()
            _processes.value = agent.processes()
        }
    }

    fun refreshAppServices() = viewModelScope.launch { task { _appServices.value = agent.appServices() } }
    fun refreshProcesses() = viewModelScope.launch { task { _processes.value = agent.processes() } }

    fun loadServiceLogs(name: String) = viewModelScope.launch {
        _serviceLogs.value = ""
        task { _serviceLogs.value = sanitize(agent.serviceLogs(name)) }
    }
    fun refreshLogs() = viewModelScope.launch { task { _logs.value = sanitize(agent.logs()) } }

    fun checkUpdates() = viewModelScope.launch {
        _updateChecking.value = true
        runCatching { UpdateChecker.check() }
            .onSuccess { release ->
                _latestRelease.value = release
                _updateAvailable.value = release?.let { UpdateChecker.isNewer(it.versionName) } == true
            }
        _updateChecking.value = false
    }

    fun applyWifi(
        ifaceSection: String,
        deviceSection: String,
        enabled: Boolean,
        ssid: String,
        encryption: String,
        password: String?,
        channel: String,
        htmode: String,
        country: String
    ) = safeApplyChange("wifi") {
        agent.setWifi(
            ifaceSection,
            deviceSection,
            enabled,
            ssid,
            encryption,
            password,
            channel,
            htmode,
            country
        )
    }

    fun applyLan(ip: String, netmask: String) = viewModelScope.launch {
        val profile = savedProfile ?: return@launch
        _busy.value = true
        _error.value = null
        try {
            val state = agent.safeBegin("network", 90)
            _safeApply.value = state
            agent.setLan(ip, netmask)
            agent.applyConfig("network")
            watchSafeApply(state)

            if (profile.host != ip) {
                ssh.disconnect()
                _connected.value = false
                delay(2500)
                val newProfile = profile.copy(host = ip)
                var connected = false
                repeat(5) {
                    if (!connected) {
                        val result = ssh.connect(newProfile)
                        if (result.isSuccess) {
                            store.saveProfile(newProfile)
                            _fingerprint.value = result.getOrNull()
                            _connected.value = true
                            connected = true
                            runCatching { refreshCoreInternal() }
                            runCatching { _networkConfig.value = agent.config() }
                        } else {
                            delay(2500)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        } finally {
            _busy.value = false
        }
    }

    fun applyDhcp(start: String, limit: String, lease: String) =
        safeApplyChange("dhcp") { agent.setDhcp(start, limit, lease) }

    fun applyDns(peer: Boolean, servers: List<String>) =
        safeApplyChange("network") { agent.setDns(peer, servers) }

    fun applyWan(
        proto: String,
        username: String,
        password: String?,
        ip: String,
        netmask: String,
        gateway: String,
        mtu: String,
        wan6Enabled: Boolean
    ) = safeApplyChange("network") {
        agent.setWan(proto, username, password, ip, netmask, gateway, mtu)
        agent.setWan6(wan6Enabled)
    }

    private fun safeApplyChange(kind: String, setter: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        try {
            val state = agent.safeBegin(kind, 90)
            _safeApply.value = state
            setter()
            agent.applyConfig(kind)
            watchSafeApply(state)
            delay(1500)
            runCatching { _network.value = agent.network() }
            runCatching { _wifi.value = agent.wifi() }
            runCatching { _networkConfig.value = agent.config() }
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        } finally {
            _busy.value = false
        }
    }

    private fun watchSafeApply(initial: SafeApplyState) = viewModelScope.launch {
        var state = initial
        while (state.active && state.secondsRemaining > 0) {
            delay(1000)
            val localRemaining = (state.deadlineEpoch - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            state = state.copy(secondsRemaining = localRemaining, active = localRemaining > 0)
            _safeApply.value = state

            if (localRemaining % 5L == 0L && ssh.isConnected()) {
                runCatching { agent.safeStatus() }.getOrNull()?.let {
                    state = it
                    _safeApply.value = it
                }
            }
        }
    }

    fun confirmSafeApply() = viewModelScope.launch {
        val tx = _safeApply.value.transactionId
        if (tx.isBlank()) return@launch
        task {
            agent.safeConfirm(tx)
            _safeApply.value = SafeApplyState()
            _network.value = agent.network()
            _wifi.value = agent.wifi()
            _networkConfig.value = agent.config()
            runCatching { _firewall.value = agent.firewallSnapshot() }
        }
    }

    fun rollbackSafeApply() = viewModelScope.launch {
        val tx = _safeApply.value.transactionId
        if (tx.isBlank()) return@launch
        task {
            agent.safeRollback(tx)
            _safeApply.value = SafeApplyState()
            delay(2500)
            runCatching { _network.value = agent.network() }
            runCatching { _wifi.value = agent.wifi() }
            runCatching { _networkConfig.value = agent.config() }
            runCatching { _firewall.value = agent.firewallSnapshot() }
        }
    }

    fun setBlocked(device: DeviceInfo, blocked: Boolean) = viewModelScope.launch {
        task {
            agent.setBlocked(device.mac, blocked)
            _devices.value = agent.devices()
        }
    }

    fun serviceAction(service: ServiceInfo, action: String) = viewModelScope.launch {
        task {
            agent.serviceAction(service.name, action)
            _services.value = agent.services()
            _appServices.value = agent.appServices()
        }
    }

    fun appServiceAction(service: AppServiceInfo, action: String) = viewModelScope.launch {
        task {
            agent.serviceAction(service.initService, action)
            _appServices.value = agent.appServices()
            _services.value = agent.services()
        }
    }

    fun restartNetwork() = viewModelScope.launch { task { agent.restartNetwork() } }

    fun reboot() = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        runCatching { agent.reboot() }
        ssh.disconnect()
        _connected.value = false
        _busy.value = false
    }

    fun terminal(command: String) = viewModelScope.launch {
        if (command.isBlank()) return@launch
        task {
            val result = sanitize(agent.raw(command))
            _terminalOutput.value += "\n$ $command\n$result\n"
        }
    }

    fun clearError() { _error.value = null }

    private suspend fun refreshCoreInternal() {
        _status.value = agent.status()
        _devices.value = agent.devices()
        _network.value = agent.network()
        _wifi.value = agent.wifi()
        runCatching { updateTrafficSample() }
        recomputeHealth()
    }

    private suspend fun task(block: suspend () -> Unit) {
        _busy.value = true
        _error.value = null
        try {
            block()
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        } finally {
            _busy.value = false
        }
    }

    private fun sanitize(text: String): String {
        return text.lineSequence().joinToString("\n") { line ->
            when {
                Regex("(?i)(password|passwd|token|secret|api[_-]?key|private[_-]?key)").containsMatchIn(line) ->
                    line.replace(Regex("([:=])[ ]*[^, ]+"), "$1 ***")
                else -> line
            }
        }
    }

    override fun onCleared() {
        ssh.disconnect()
        super.onCleared()
    }
}
