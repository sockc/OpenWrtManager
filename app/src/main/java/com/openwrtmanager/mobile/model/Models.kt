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
    val blocked: Boolean
)

data class NetworkSummary(
    val wanProto: String = "",
    val wanDevice: String = "",
    val wanIpv4: String = "",
    val wanIpv6: String = "",
    val wanUptime: Long = 0,
    val lanDevice: String = "",
    val lanIpv4: String = "",
    val lanIpv6: String = ""
)

data class WifiNetwork(
    val section: String,
    val kind: String,
    val device: String = "",
    val ssid: String = "",
    val encryption: String = "",
    val channel: String = "",
    val band: String = "",
    val htmode: String = "",
    val disabled: Boolean = false
)

data class ServiceInfo(
    val name: String,
    val enabled: Boolean,
    val running: Boolean
)
