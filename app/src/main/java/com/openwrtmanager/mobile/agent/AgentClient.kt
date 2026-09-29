package com.openwrtmanager.mobile.agent

import android.content.Context
import com.openwrtmanager.mobile.R
import com.openwrtmanager.mobile.model.*
import com.openwrtmanager.mobile.ssh.SshManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class AgentClient(private val context: Context, private val ssh: SshManager) {
    companion object { const val BUNDLED_AGENT_VERSION = "0.1.6" }

    suspend fun agentVersion(): String? = runCatching {
        val out = ssh.exec("/usr/bin/owm-agent version 2>/dev/null")
        JSONObject(out).optString("version").ifBlank { null }
    }.getOrNull()

    suspend fun installAgent(): String {
        val agentScript = context.resources.openRawResource(R.raw.owm_agent).use { it.readBytes() }
        val configScript = context.resources.openRawResource(R.raw.owm_config).use { it.readBytes() }
        ssh.upload(agentScript, "/tmp/owm-agent")
        ssh.upload(configScript, "/tmp/owm-config")
        return ssh.exec(
            "chmod 700 /tmp/owm-agent /tmp/owm-config && /tmp/owm-agent install && /tmp/owm-config install",
            30_000
        )
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
            cpuPercent = o.optInt("cpu_percent"),
            memTotalKb = o.optLong("mem_total_kb"),
            memAvailableKb = o.optLong("mem_available_kb"),
            rootTotalKb = o.optLong("root_total_kb"),
            rootFreeKb = o.optLong("root_free_kb"),
            temperatureC = if (o.isNull("temperature_c")) null else o.optDouble("temperature_c")
        )
    }

    suspend fun devices(): List<DeviceInfo> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent devices"))
        val parsed = buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val mac = o.optString("mac").trim().uppercase()
                if (mac.isBlank()) continue
                add(
                    DeviceInfo(
                        ip = o.optString("ip").trim(),
                        mac = mac,
                        hostname = o.optString("hostname").ifBlank { "未知设备" },
                        interfaceName = o.optString("interface"),
                        state = o.optString("state"),
                        connectionType = o.optString("type", "unknown"),
                        blocked = o.optBoolean("blocked", false),
                        band = o.optString("band"),
                        signalDbm = if (o.isNull("signal_dbm")) null else o.optInt("signal_dbm")
                    )
                )
            }
        }

        fun rank(d: DeviceInfo): Int = when (d.state.uppercase()) {
            "REACHABLE" -> 60
            "DELAY" -> 50
            "PROBE" -> 40
            "STALE" -> 30
            "PERMANENT" -> 20
            else -> 10
        } + (if (d.ip.contains('.')) 5 else 0) + (if (d.connectionType == "wifi") 3 else 0)

        return parsed
            .groupBy { it.mac }
            .values
            .map { sameMac -> sameMac.maxByOrNull(::rank) ?: sameMac.first() }
            .sortedWith(
                compareBy<DeviceInfo> { it.hostname == "未知设备" }
                    .thenBy { it.hostname.lowercase() }
                    .thenBy { it.ip }
            )
    }

    suspend fun network(): NetworkSummary {
        val o = JSONObject(ssh.exec("/usr/bin/owm-agent network"))
        val dns = buildList {
            val a = o.optJSONArray("wan_dns")
            if (a != null) for (i in 0 until a.length()) add(a.optString(i))
        }
        return NetworkSummary(
            wanProto = o.optString("wan_proto"),
            wanDevice = o.optString("wan_device"),
            wanIpv4 = o.optString("wan_ipv4"),
            wanIpv6 = o.optString("wan_ipv6"),
            wanUptime = o.optLong("wan_uptime"),
            wanUp = o.optBoolean("wan_up"),
            wanGateway = o.optString("wan_gateway"),
            wanDns = dns,
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
                add(
                    WifiNetwork(
                        section = o.optString("section"),
                        kind = o.optString("kind"),
                        device = o.optString("device"),
                        ifname = o.optString("ifname"),
                        ssid = o.optString("ssid"),
                        encryption = o.optString("encryption"),
                        channel = o.optString("channel"),
                        band = o.optString("band"),
                        htmode = o.optString("htmode"),
                        country = o.optString("country"),
                        disabled = o.optBoolean("disabled", false),
                        clientCount = o.optInt("client_count"),
                        keySet = o.optBoolean("key_set", false)
                    )
                )
            }
        }
    }

    suspend fun config(): NetworkConfig {
        val o = JSONObject(ssh.exec("/usr/bin/owm-config config"))
        val dns = buildList {
            val a = o.optJSONArray("dns_servers")
            if (a != null) for (i in 0 until a.length()) add(a.optString(i))
        }
        return NetworkConfig(
            lanIp = o.optString("lan_ip"),
            lanNetmask = o.optString("lan_netmask"),
            dhcpStart = o.optString("dhcp_start"),
            dhcpLimit = o.optString("dhcp_limit"),
            dhcpLeaseTime = o.optString("dhcp_leasetime"),
            dnsPeer = o.optBoolean("dns_peer", true),
            dnsServers = dns,
            wanProto = o.optString("wan_proto"),
            wanUsername = o.optString("wan_username"),
            wanPasswordSet = o.optBoolean("wan_password_set"),
            wanMtu = o.optString("wan_mtu"),
            wanIp = o.optString("wan_ip"),
            wanNetmask = o.optString("wan_netmask"),
            wanGateway = o.optString("wan_gateway"),
            wan6Enabled = o.optBoolean("wan6_enabled", true)
        )
    }

    suspend fun safeBegin(kind: String, timeoutSeconds: Int = 90): SafeApplyState {
        val safeKind = kind.replace(Regex("[^A-Za-z0-9_-]"), "")
        val o = JSONObject(
            ssh.exec(
                "/usr/bin/owm-config safe-begin '$safeKind' ${timeoutSeconds.coerceIn(45, 180)}",
                20_000
            )
        )
        return safeStateFromJson(o)
    }

    suspend fun safeStatus(): SafeApplyState =
        safeStateFromJson(JSONObject(ssh.exec("/usr/bin/owm-config safe-status")))

    suspend fun safeConfirm(transactionId: String) {
        val tx = transactionId.replace(Regex("[^A-Za-z0-9_.-]"), "")
        ssh.exec("/usr/bin/owm-config safe-confirm '$tx'", 15_000)
    }

    suspend fun safeRollback(transactionId: String) {
        val tx = transactionId.replace(Regex("[^A-Za-z0-9_.-]"), "")
        ssh.exec("/usr/bin/owm-config safe-rollback '$tx'", 20_000)
    }

    suspend fun setWifi(
        ifaceSection: String,
        deviceSection: String,
        enabled: Boolean,
        ssid: String,
        encryption: String,
        password: String?,
        channel: String,
        htmode: String,
        country: String
    ) {
        val args = listOf(
            safeName(ifaceSection),
            safeName(deviceSection),
            if (enabled) "1" else "0",
            b64(ssid),
            safeToken(encryption),
            b64(password.orEmpty()),
            safeToken(channel.ifBlank { "auto" }),
            safeToken(htmode),
            safeToken(country.uppercase())
        ).joinToString(" ") { "'$it'" }
        ssh.exec("/usr/bin/owm-config set-wifi $args", 20_000)
    }

    suspend fun setLan(ip: String, netmask: String) {
        ssh.exec("/usr/bin/owm-config set-lan '${safeToken(ip)}' '${safeToken(netmask)}'", 20_000)
    }

    suspend fun setDhcp(start: String, limit: String, leaseTime: String) {
        ssh.exec(
            "/usr/bin/owm-config set-dhcp '${safeToken(start)}' '${safeToken(limit)}' '${safeToken(leaseTime)}'",
            20_000
        )
    }

    suspend fun setDns(peer: Boolean, servers: List<String>) {
        val packed = servers.filter { it.isNotBlank() }.joinToString(" ")
        ssh.exec(
            "/usr/bin/owm-config set-dns '${if (peer) "1" else "0"}' '${b64(packed)}'",
            20_000
        )
    }

    suspend fun setWan(
        proto: String,
        username: String,
        password: String?,
        ip: String,
        netmask: String,
        gateway: String,
        mtu: String
    ) {
        val args = listOf(
            safeToken(proto),
            b64(username),
            b64(password.orEmpty()),
            safeToken(ip),
            safeToken(netmask),
            safeToken(gateway),
            safeToken(mtu)
        ).joinToString(" ") { "'$it'" }
        ssh.exec("/usr/bin/owm-config set-wan $args", 20_000)
    }

    suspend fun setWan6(enabled: Boolean) {
        ssh.exec("/usr/bin/owm-config set-wan6 '${if (enabled) "1" else "0"}'", 20_000)
    }

    suspend fun applyConfig(kind: String) {
        val safeKind = kind.replace(Regex("[^A-Za-z0-9_-]"), "")
        ssh.exec("/usr/bin/owm-config apply '$safeKind'", 10_000)
    }



    suspend fun firewallSnapshot(): FirewallSnapshot {
        val o = JSONObject(ssh.exec("/usr/bin/owm-config firewall-list", 20_000))

        val zones = buildList {
            val a = o.optJSONArray("zones") ?: JSONArray()
            for (i in 0 until a.length()) {
                val z = a.optJSONObject(i) ?: continue
                val networks = buildList {
                    val n = z.optJSONArray("networks")
                    if (n != null) for (j in 0 until n.length()) add(n.optString(j))
                }
                add(
                    FirewallZone(
                        name = z.optString("name"),
                        input = z.optString("input"),
                        output = z.optString("output"),
                        forward = z.optString("forward"),
                        masquerading = z.optBoolean("masquerading"),
                        mtuFix = z.optBoolean("mtu_fix"),
                        networks = networks
                    )
                )
            }
        }

        val redirects = buildList {
            val a = o.optJSONArray("redirects") ?: JSONArray()
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                add(
                    PortForwardRule(
                        index = r.optInt("index"),
                        name = r.optString("name"),
                        enabled = r.optBoolean("enabled", true),
                        src = r.optString("src", "wan"),
                        srcPort = r.optString("src_port"),
                        dest = r.optString("dest", "lan"),
                        destIp = r.optString("dest_ip"),
                        destPort = r.optString("dest_port"),
                        proto = r.optString("proto", "tcp")
                    )
                )
            }
        }

        val rules = buildList {
            val a = o.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                add(
                    TrafficRule(
                        index = r.optInt("index"),
                        name = r.optString("name"),
                        enabled = r.optBoolean("enabled", true),
                        src = r.optString("src"),
                        dest = r.optString("dest"),
                        proto = r.optString("proto"),
                        srcPort = r.optString("src_port"),
                        destPort = r.optString("dest_port"),
                        target = r.optString("target")
                    )
                )
            }
        }

        return FirewallSnapshot(zones = zones, redirects = redirects, rules = rules)
    }

    suspend fun addPortForward(
        name: String,
        srcPort: String,
        destIp: String,
        destPort: String,
        proto: String,
        enabled: Boolean
    ) {
        ssh.exec(
            "/usr/bin/owm-config firewall-add-redirect '${b64(name)}' '${safeToken(srcPort)}' " +
                "'${safeToken(destIp)}' '${safeToken(destPort)}' '${safeToken(proto)}' " +
                "'${if (enabled) "1" else "0"}'",
            20_000
        )
    }

    suspend fun updatePortForward(rule: PortForwardRule) {
        ssh.exec(
            "/usr/bin/owm-config firewall-set-redirect '${rule.index}' '${b64(rule.name)}' " +
                "'${safeToken(rule.srcPort)}' '${safeToken(rule.destIp)}' '${safeToken(rule.destPort)}' " +
                "'${safeToken(rule.proto)}' '${if (rule.enabled) "1" else "0"}'",
            20_000
        )
    }

    suspend fun deletePortForward(index: Int) {
        ssh.exec("/usr/bin/owm-config firewall-delete-redirect '${index.coerceAtLeast(0)}'", 20_000)
    }

    suspend fun toggleTrafficRule(index: Int, enabled: Boolean) {
        ssh.exec(
            "/usr/bin/owm-config firewall-toggle-rule '${index.coerceAtLeast(0)}' '${if (enabled) "1" else "0"}'",
            20_000
        )
    }


    suspend fun packageStatus(): PackageManagerStatus {
        val o = JSONObject(ssh.exec("/usr/bin/owm-agent pkg-status", 20_000))
        return PackageManagerStatus(
            manager = o.optString("manager"),
            installedCount = o.optInt("installed_count"),
            upgradableCount = o.optInt("upgradable_count"),
            overlayFreeKb = o.optLong("overlay_free_kb")
        )
    }

    suspend fun installedPackages(): List<PackageInfo> =
        parsePackages(ssh.exec("/usr/bin/owm-agent pkg-installed", 40_000))

    suspend fun upgradablePackages(): List<PackageInfo> =
        parsePackages(ssh.exec("/usr/bin/owm-agent pkg-upgradable", 40_000))

    suspend fun searchPackages(query: String): List<PackageInfo> {
        val q = query.replace(Regex("[^A-Za-z0-9_.+@-]"), "").take(64)
        if (q.length < 2) return emptyList()
        return parsePackages(ssh.exec("/usr/bin/owm-agent pkg-search '$q'", 60_000))
    }

    suspend fun updatePackageLists(): String =
        ssh.exec("/usr/bin/owm-agent pkg-update", 120_000)

    suspend fun installPackage(name: String): String =
        ssh.exec("/usr/bin/owm-agent pkg-install '${safeName(name)}'", 180_000)

    suspend fun upgradePackage(name: String): String =
        ssh.exec("/usr/bin/owm-agent pkg-upgrade '${safeName(name)}'", 180_000)

    suspend fun removePackage(name: String): String =
        ssh.exec("/usr/bin/owm-agent pkg-remove '${safeName(name)}'", 120_000)

    suspend fun createBackup(): Pair<BackupInfo, ByteArray> {
        val o = JSONObject(ssh.exec("/usr/bin/owm-config backup-create", 60_000))
        val path = o.optString("path")
        if (!path.startsWith("/tmp/OpenWrtManager-") || !path.endsWith(".tar.gz")) {
            error("备份路径无效")
        }
        val info = BackupInfo(
            filename = o.optString("filename").ifBlank { "OpenWrtManager-backup.tar.gz" },
            sizeBytes = o.optLong("size_bytes"),
            includesPackageList = o.optBoolean("includes_package_list", false)
        )
        val bytes = try {
            ssh.download(path)
        } finally {
            runCatching { ssh.exec("/usr/bin/owm-config backup-delete '$path'", 10_000) }
        }
        return info to bytes
    }

    suspend fun restoreBackup(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "备份文件为空" }
        require(bytes.size <= 32 * 1024 * 1024) { "备份文件超过 32 MB" }
        val path = "/tmp/owm-restore-${System.currentTimeMillis()}.tar.gz"
        ssh.upload(bytes, path)
        return try {
            ssh.exec("/usr/bin/owm-config backup-restore '$path'", 120_000)
        } catch (t: Throwable) {
            runCatching { ssh.exec("/usr/bin/owm-config backup-delete '$path'", 10_000) }
            throw t
        }
    }

    private fun parsePackages(json: String): List<PackageInfo> {
        val a = JSONArray(json)
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                add(
                    PackageInfo(
                        name = name,
                        version = o.optString("version"),
                        availableVersion = o.optString("available_version"),
                        description = o.optString("description"),
                        installed = o.optBoolean("installed"),
                        upgradable = o.optBoolean("upgradable"),
                        protected = o.optBoolean("protected")
                    )
                )
            }
        }
    }

    suspend fun appServices(): List<AppServiceInfo> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent app-services", 25_000))
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ports = buildList {
                    val p = o.optJSONArray("ports")
                    if (p != null) for (j in 0 until p.length()) add(p.optInt(j))
                }
                add(
                    AppServiceInfo(
                        id = o.optString("id"),
                        displayName = o.optString("display_name"),
                        initService = o.optString("init_service"),
                        controllable = o.optBoolean("controllable", true),
                        running = o.optBoolean("running"),
                        enabled = o.optBoolean("enabled"),
                        health = o.optString("health", "unknown"),
                        version = o.optString("version"),
                        pid = if (o.isNull("pid")) null else o.optInt("pid"),
                        cpuPercent = if (o.isNull("cpu_percent")) null else o.optDouble("cpu_percent"),
                        memoryKb = if (o.isNull("memory_kb")) null else o.optLong("memory_kb"),
                        ports = ports,
                        detail = o.optString("detail")
                    )
                )
            }
        }
    }

    suspend fun processes(): List<ProcessInfo> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent processes", 25_000))
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(
                    ProcessInfo(
                        pid = o.optInt("pid"),
                        name = o.optString("name"),
                        user = o.optString("user"),
                        cpuPercent = o.optDouble("cpu_percent"),
                        memoryPercent = o.optDouble("memory_percent"),
                        rssKb = o.optLong("rss_kb"),
                        command = o.optString("command"),
                        protected = o.optBoolean("protected")
                    )
                )
            }
        }.sortedByDescending { it.rssKb }
    }

    suspend fun serviceLogs(name: String, lines: Int = 120): String {
        val safe = name.replace(Regex("[^A-Za-z0-9_.@+-]"), "")
        return ssh.exec("/usr/bin/owm-agent service-logs '$safe' ${lines.coerceIn(20, 300)}", 20_000)
    }

    suspend fun services(): List<ServiceInfo> {
        val a = JSONArray(ssh.exec("/usr/bin/owm-agent services", 25_000))
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(
                    ServiceInfo(
                        name = o.optString("name"),
                        enabled = o.optBoolean("enabled"),
                        running = o.optBoolean("running"),
                        protected = o.optBoolean("protected", false)
                    )
                )
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

    suspend fun logs(lines: Int = 200): String =
        ssh.exec("/usr/bin/owm-agent logs ${lines.coerceIn(20, 1000)}")

    suspend fun restartNetwork(): String =
        ssh.exec("/usr/bin/owm-agent network-restart", 20_000)

    suspend fun reboot(): String =
        ssh.exec("/usr/bin/owm-agent reboot", 8_000)

    suspend fun raw(command: String): String =
        ssh.exec(command, 30_000)

    private fun safeStateFromJson(o: JSONObject): SafeApplyState =
        SafeApplyState(
            active = o.optBoolean("active", false),
            transactionId = o.optString("transaction_id"),
            deadlineEpoch = o.optLong("deadline_epoch"),
            secondsRemaining = o.optLong("seconds_remaining"),
            kind = o.optString("kind")
        )

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.@+-]"), "")

    private fun safeToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.:/@+-]"), "")
}
