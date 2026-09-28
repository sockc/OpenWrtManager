package com.openwrtmanager.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmanager.mobile.agent.AgentClient
import com.openwrtmanager.mobile.data.SecureStore
import com.openwrtmanager.mobile.model.*
import com.openwrtmanager.mobile.ssh.SshManager
import com.openwrtmanager.mobile.update.UpdateChecker
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

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()
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

    fun refreshServices() = viewModelScope.launch { task { _services.value = agent.services() } }
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
        mtu: String
    ) = safeApplyChange("network") {
        agent.setWan(proto, username, password, ip, netmask, gateway, mtu)
    }

    fun applyWan6(enabled: Boolean) =
        safeApplyChange("network") { agent.setWan6(enabled) }

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
