# OpenWrt Manager V0.1.0

Android 原生 OpenWrt/Kwrt 管理客户端。V0.1.0 针对现代 OpenWrt/Kwrt（ubus/UCI/firewall4/nftables）设计，不依赖 LuCI 插件。

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
