package com.openwrtmanager.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmanager.mobile.agent.AgentClient
import com.openwrtmanager.mobile.data.SecureStore
import com.openwrtmanager.mobile.model.*
import com.openwrtmanager.mobile.ssh.SshManager
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
    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()
    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs.asStateFlow()
    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    val savedProfile: RouterProfile? get() = store.loadProfile()

    fun connect(profile: RouterProfile) = viewModelScope.launch {
        task {
            val fp = ssh.connect(profile).getOrThrow()
            store.saveProfile(profile)
            _fingerprint.value = fp
            _connected.value = true
            val installedVersion = agent.agentVersion()
            _agentInstalled.value = installedVersion != null
            if (_agentInstalled.value) {
                // Keep the tiny router-side agent in sync with the APK.
                if (installedVersion != AgentClient.BUNDLED_AGENT_VERSION) {
                    agent.installAgent()
                }
                refreshCoreInternal()
            }
        }
    }

    fun disconnect() {
        ssh.disconnect()
        _connected.value = false
        _status.value = null
    }

    fun installAgent() = viewModelScope.launch {
        task {
            agent.installAgent()
            _agentInstalled.value = agent.agentVersion() != null
            refreshCoreInternal()
        }
    }

    fun refreshHome() = viewModelScope.launch { task { _status.value = agent.status() } }
    fun refreshDevices() = viewModelScope.launch { task { _devices.value = agent.devices() } }
    fun refreshNetwork() = viewModelScope.launch { task { _network.value = agent.network(); _wifi.value = agent.wifi() } }
    fun refreshServices() = viewModelScope.launch { task { _services.value = agent.services() } }
    fun refreshLogs() = viewModelScope.launch { task { _logs.value = agent.logs() } }

    fun setBlocked(device: DeviceInfo, blocked: Boolean) = viewModelScope.launch {
        task { agent.setBlocked(device.mac, blocked); _devices.value = agent.devices() }
    }

    fun serviceAction(service: ServiceInfo, action: String) = viewModelScope.launch {
        task { agent.serviceAction(service.name, action); _services.value = agent.services() }
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
            val result = agent.raw(command)
            _terminalOutput.value += "\n$ $command\n$result\n"
        }
    }

    fun clearError() { _error.value = null }

    private suspend fun refreshCoreInternal() {
        _status.value = agent.status()
        _devices.value = agent.devices()
        _network.value = agent.network()
    }

    private suspend fun task(block: suspend () -> Unit) {
        _busy.value = true
        _error.value = null
        try { block() } catch (t: Throwable) { _error.value = t.message ?: t.javaClass.simpleName }
        finally { _busy.value = false }
    }

    override fun onCleared() {
        ssh.disconnect()
        super.onCleared()
    }
}
