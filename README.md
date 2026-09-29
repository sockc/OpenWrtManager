# OpenWrt Manager V0.1.1

Android 原生 OpenWrt/Kwrt 管理客户端。V0.1.1 针对现代 OpenWrt/Kwrt（ubus/UCI/firewall4/nftables）设计，不依赖 LuCI 插件。


## V0.1.1 修复

- 修复“设备”页因同一 MAC 出现多个邻居记录而闪退的问题。
- 设备列表 APP 端按 MAC 去重，并优先保留 REACHABLE / IPv4 记录。
- Router Agent 只输出 IPv4 邻居并按 MAC 去重，过滤 FAILED 条目。
- 移除 Compose 设备列表对 MAC 唯一 key 的硬依赖，避免异常数据导致界面崩溃。
- APP 连接时自动升级旧版 Router Agent。
- GitHub Actions 适配 KEYSTORE / STOREPASSWORD / KEYALIAS / KEYPASSWORD Secrets，并加强签名文件校验。

## V0.1.0 已实现

- SSH 登录路由器，首次连接保存 SSH 主机指纹（TOFU）
- Android Keystore 加密保存路由器登录资料
- 一键安装轻量 `owm-agent`（无监听端口）
- 系统状态：型号、固件、内核、架构、负载、内存、存储、温度、运行时间
- 在线设备：IP / MAC / 主机名 / 接口 / Wi-Fi 或有线识别
- 设备一键断网 / 恢复，规则由 nftables 执行并持久化
- WAN / LAN 状态
- Wi-Fi radio / SSID 信息
- OpenWrt 服务列表、启动、停止、重启、开机自启开关
- 系统日志
- SSH 命令终端（高级功能兜底）
- 重启网络、重启路由器
- 固定 applicationId 与正式签名方案

## 路由端组件

APP 首次连接后把 `owm-agent` 上传到路由器：

- `/usr/bin/owm-agent`
- `/etc/openwrt-manager/`

Agent **不会开启 HTTP/TCP 监听端口**；V0.1.0 仍通过 SSH 调用，攻击面比常驻自定义 Web API 更小。

## 断网实现

通过 UCI 创建独立的 firewall4 MAC 规则；firewall4 最终编译为 nftables 规则。配置写入 `/etc/config/firewall`，防火墙 reload 和路由器重启后都会保留。

## 下一版计划

V0.2：Wi-Fi 编辑、安全应用/自动回滚、DHCP/DNS、端口转发、防火墙规则、软件包管理、配置备份。

V0.3：实时网速、按设备流量、限速、时间计划、VPN/Tailscale、固件升级、多路由器。

## 构建环境

- JDK 17
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- compileSdk / targetSdk 35
- minSdk 26

签名说明见 `docs/FIXED_SIGNING.md`。


## V0.1.2 development

- GitHub Releases update checking in the Android app
- richer Cudy TR3000 / Kwrt system, network and Wi-Fi status
- device band and signal reporting
- searchable/redacted logs
- router agent 0.1.2


## V0.1.3 Safe Apply

- Router-side rollback transaction before risky configuration changes
- 90-second confirmation window with automatic restore
- Editable Wi-Fi, LAN, DHCP, DNS, WAN and WAN6 settings
- PPPoE passwords are never returned to the app
- Separate `owm-config` helper for configuration writes


## V0.1.4 Service Center

- Service Center tabs: application services, system services, and processes.
- Application-service discovery for Nikki, OpenClash, PassWall/PassWall2, HomeProxy, Mihomo, sing-box, Tailscale, ZeroTier, AdGuard Home, SmartDNS, MosDNS, Docker, Samba, and DDNS.
- Application-service status, PID, memory use, autostart state, start/restart, autostart toggle, and per-service logs.
- System-service protection is enforced both in the Android UI and router agent for critical networking services.
- Process inventory is read-only in V0.1.4; critical processes are marked and no arbitrary process termination is exposed.


## V0.1.5 Firewall Center

- New firewall center under More: port forwarding, traffic rules, and zone overview.
- Port forwards can be added, edited, enabled/disabled, and deleted.
- TCP, UDP, and TCP+UDP mappings are supported.
- Existing traffic rules are readable and can be enabled/disabled; full rule editing remains deferred.
- Firewall zones are read-only in V0.1.5 to avoid accidental WAN/LAN isolation changes.
- Every firewall mutation uses the existing 90-second Safe Apply transaction with router-side rollback.


## V0.1.6 Package Manager and Backup

- OPKG package manager UI for installed packages, repository search, install, individual upgrades, and removal.
- Core/essential package protections are enforced in the router agent; V0.1.6 intentionally does not provide a bulk "upgrade all" action.
- Package feed refresh and Overlay free-space status are shown in the app.
- Standard OpenWrt configuration backups are generated with sysupgrade and exported to the Android document picker.
- When supported, backups include the installed-package list via `sysupgrade -k -b`; older variants fall back to `sysupgrade -b`.
- Restore accepts a selected OpenWrt `.tar.gz` backup, validates the archive and path layout, then uses `sysupgrade -r`. A reboot is required after restore.


## V0.1.7 Realtime Monitoring and Diagnostics

- Realtime WAN byte-rate sampling uses interface counters from `/sys/class/net/*/statistics` and computes rates in the Android app, avoiding long-running router sampling commands.
- Home dashboard shows current WAN download/upload rates plus rolling 1-, 5-, and 15-minute averages.
- Device presence now distinguishes verified online clients from cached neighbour entries; Wi-Fi association or active neighbour states are treated as online, while STALE entries are shown as recently seen.
- Network diagnostics test WAN state, default route, gateway reachability, public-IP reachability, local DNS resolution, and pending Safe Apply transactions.
- Interface diagnostics show RX/TX counters, errors, and dropped packets.
- Health checks surface WAN failures, low memory, low storage, high temperature, and pending Safe Apply state.
- Diagnostic reports intentionally omit passwords, tokens, SSIDs, MAC addresses, and IP addresses.


## V0.1.8 Device Center 3.0

- Device identity now keeps multiple observed IPv4/IPv6 addresses for the same MAC while retaining one primary address for compact lists.
- The device page is upgraded to Device Center 3.0 with online/recent separation, details, Wi-Fi band/signal/interface data and current traffic ranking.
- Per-device traffic uses nlbwmon when available. The app detects the backend, never invents traffic values, and offers an explicit nlbwmon install action when the package is missing.
- Per-device realtime rates are calculated in the Android app from successive nlbwmon MAC-grouped counters. The router only returns accounting counters.
- Cumulative traffic is labelled as the current nlbwmon accounting period rather than "today", because nlbwmon periods are configurable and are monthly by default.
- The home dashboard can show the current highest-traffic device when nlbwmon data is available.
