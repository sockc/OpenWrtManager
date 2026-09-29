package com.openwrtmanager.mobile.model

data class RouterProfile(
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    val password: String = ""
)

data class SystemStatus(
    val hostname: String = "OpenWrt",
    val model: String = "",
    val firmware: String = "",
    val kernel: String = "",
    val arch: String = "",
    val uptimeSeconds: Long = 0,
    val load1: Double = 0.0,
    val cpuPercent: Int = 0,
    val memTotalKb: Long = 0,
    val memAvailableKb: Long = 0,
    val rootTotalKb: Long = 0,
    val rootFreeKb: Long = 0,
    val temperatureC: Double? = null
)

data class DeviceInfo(
    val ip: String,
    val mac: String,
    val hostname: String,
    val interfaceName: String,
    val state: String,
    val connectionType: String,
    val blocked: Boolean,
    val band: String = "",
    val signalDbm: Int? = null
)

data class NetworkSummary(
    val wanProto: String = "",
    val wanDevice: String = "",
    val wanIpv4: String = "",
    val wanIpv6: String = "",
    val wanUptime: Long = 0,
    val wanUp: Boolean = false,
    val wanGateway: String = "",
    val wanDns: List<String> = emptyList(),
    val lanDevice: String = "",
    val lanIpv4: String = "",
    val lanIpv6: String = ""
)

data class WifiNetwork(
    val section: String,
    val kind: String,
    val device: String = "",
    val ifname: String = "",
    val ssid: String = "",
    val encryption: String = "",
    val channel: String = "",
    val band: String = "",
    val htmode: String = "",
    val country: String = "",
    val disabled: Boolean = false,
    val clientCount: Int = 0,
    val keySet: Boolean = false
)

data class NetworkConfig(
    val lanIp: String = "",
    val lanNetmask: String = "",
    val dhcpStart: String = "",
    val dhcpLimit: String = "",
    val dhcpLeaseTime: String = "",
    val dnsPeer: Boolean = true,
    val dnsServers: List<String> = emptyList(),
    val wanProto: String = "",
    val wanUsername: String = "",
    val wanPasswordSet: Boolean = false,
    val wanMtu: String = "",
    val wanIp: String = "",
    val wanNetmask: String = "",
    val wanGateway: String = "",
    val wan6Enabled: Boolean = true
)

data class SafeApplyState(
    val active: Boolean = false,
    val transactionId: String = "",
    val deadlineEpoch: Long = 0,
    val secondsRemaining: Long = 0,
    val kind: String = ""
)

data class ServiceInfo(
    val name: String,
    val enabled: Boolean,
    val running: Boolean,
    val protected: Boolean = false
)

data class AppServiceInfo(
    val id: String,
    val displayName: String,
    val initService: String,
    val controllable: Boolean = true,
    val running: Boolean,
    val enabled: Boolean,
    val health: String = "unknown",
    val version: String = "",
    val pid: Int? = null,
    val cpuPercent: Double? = null,
    val memoryKb: Long? = null,
    val ports: List<Int> = emptyList(),
    val detail: String = ""
)

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val user: String = "",
    val cpuPercent: Double = 0.0,
    val memoryPercent: Double = 0.0,
    val rssKb: Long = 0,
    val command: String = "",
    val protected: Boolean = false
)

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val body: String,
    val apkUrl: String,
    val htmlUrl: String
)


data class FirewallZone(
    val name: String,
    val input: String = "",
    val output: String = "",
    val forward: String = "",
    val masquerading: Boolean = false,
    val mtuFix: Boolean = false,
    val networks: List<String> = emptyList()
)

data class PortForwardRule(
    val index: Int,
    val name: String,
    val enabled: Boolean,
    val src: String = "wan",
    val srcPort: String = "",
    val dest: String = "lan",
    val destIp: String = "",
    val destPort: String = "",
    val proto: String = "tcp"
)

data class TrafficRule(
    val index: Int,
    val name: String,
    val enabled: Boolean,
    val src: String = "",
    val dest: String = "",
    val proto: String = "",
    val srcPort: String = "",
    val destPort: String = "",
    val target: String = ""
)

data class FirewallSnapshot(
    val zones: List<FirewallZone> = emptyList(),
    val redirects: List<PortForwardRule> = emptyList(),
    val rules: List<TrafficRule> = emptyList()
)


data class PackageManagerStatus(
    val manager: String = "",
    val installedCount: Int = 0,
    val upgradableCount: Int = 0,
    val overlayFreeKb: Long = 0
)

data class PackageInfo(
    val name: String,
    val version: String = "",
    val availableVersion: String = "",
    val description: String = "",
    val installed: Boolean = false,
    val upgradable: Boolean = false,
    val protected: Boolean = false
)

data class BackupInfo(
    val filename: String,
    val sizeBytes: Long,
    val includesPackageList: Boolean = true
)
