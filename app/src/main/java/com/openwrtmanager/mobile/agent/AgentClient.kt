package com.openwrtmanager.mobile.agent

import android.content.Context
import com.openwrtmanager.mobile.R
import com.openwrtmanager.mobile.model.*
import com.openwrtmanager.mobile.ssh.SshManager
import org.json.JSONArray
import org.json.JSONObject

class AgentClient(private val context: Context, private val ssh: SshManager) {
    suspend fun agentVersion(): String? = runCatching {
        val out = ssh.exec("/usr/bin/owm-agent version 2>/dev/null")
        JSONObject(out).optString("version").ifBlank { null }
    }.getOrNull()

    suspend fun installAgent(): String {
        val script = context.resources.openRawResource(R.raw.owm_agent).use { it.readBytes() }
        ssh.upload(script, "/tmp/owm-agent")
        return ssh.exec("chmod 700 /tmp/owm-agent && /tmp/owm-agent install", 30_000)
    }

    suspend fun status(): SystemStatus {
        val o = JSONObject(ssh.exec("/usr/bin/owm-agent status"))
        return SystemStatus(
            hostname = o.optString("hostname", "OpenWrt"),
            model = o.optString("model"),
            firmware = o.optString("firmware"),
            kernel = o.optString("kernel"),
            arch = o.optString("arch"),
            uptimeSeconds = o.optLong("uptime"),
            load1 = o.optDouble("load1"),
            memTotalKb = o.optLong("mem_total_kb"),
            memAvailableKb = o.optLong("mem_available_kb"),
            rootTotalKb = o.optLong("root_total_kb"),
            rootFreeKb = o.optLong("root_free_kb"),
            temperatureC = if (o.isNull("temperature_c")) null else o.optDouble("temperature_c")
        )
    }

    suspend fun devices(): List<DeviceInfo> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent devices"))
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(DeviceInfo(
                    ip = o.optString("ip"),
                    mac = o.optString("mac"),
                    hostname = o.optString("hostname").ifBlank { "未知设备" },
                    interfaceName = o.optString("interface"),
                    state = o.optString("state"),
                    connectionType = o.optString("type", "unknown"),
                    blocked = o.optBoolean("blocked", false)
                ))
            }
        }
    }

    suspend fun network(): NetworkSummary {
        val o = JSONObject(ssh.exec("/usr/bin/owm-agent network"))
        return NetworkSummary(
            wanProto = o.optString("wan_proto"),
            wanDevice = o.optString("wan_device"),
            wanIpv4 = o.optString("wan_ipv4"),
            wanIpv6 = o.optString("wan_ipv6"),
            wanUptime = o.optLong("wan_uptime"),
            lanDevice = o.optString("lan_device"),
            lanIpv4 = o.optString("lan_ipv4"),
            lanIpv6 = o.optString("lan_ipv6")
        )
    }

    suspend fun wifi(): List<WifiNetwork> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent wifi"))
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(WifiNetwork(
                    section = o.optString("section"),
                    kind = o.optString("kind"),
                    device = o.optString("device"),
                    ssid = o.optString("ssid"),
                    encryption = o.optString("encryption"),
                    channel = o.optString("channel"),
                    band = o.optString("band"),
                    htmode = o.optString("htmode"),
                    disabled = o.optBoolean("disabled", false)
                ))
            }
        }
    }

    suspend fun services(): List<ServiceInfo> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent services", 25_000))
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ServiceInfo(o.optString("name"), o.optBoolean("enabled"), o.optBoolean("running")))
            }
        }
    }

    suspend fun setBlocked(mac: String, blocked: Boolean) {
        val action = if (blocked) "block" else "unblock"
        ssh.exec("/usr/bin/owm-agent $action '${mac.replace("'", "")}'", 20_000)
    }

    suspend fun serviceAction(name: String, action: String) {
        ssh.exec("/usr/bin/owm-agent service '${name.replace("'", "")}' '${action.replace("'", "")}'", 20_000)
    }

    suspend fun logs(lines: Int = 200): String = ssh.exec("/usr/bin/owm-agent logs ${lines.coerceIn(20, 1000)}")
    suspend fun restartNetwork(): String = ssh.exec("/usr/bin/owm-agent network-restart", 20_000)
    suspend fun reboot(): String = ssh.exec("/usr/bin/owm-agent reboot", 8_000)
    suspend fun raw(command: String): String = ssh.exec(command, 30_000)
}
