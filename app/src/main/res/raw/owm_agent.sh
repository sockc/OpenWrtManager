#!/bin/sh
# OpenWrt Manager Agent V0.1.7
# Command agent used over SSH. It opens no listening socket.
set -u
VERSION="0.1.7"
BASE="/etc/openwrt-manager"
BLOCKED="$BASE/blocked_macs"
SELF="/usr/bin/owm-agent"

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r/\\r/g'
}
q() { printf '"%s"' "$(json_escape "${1:-}")"; }
valid_mac() { echo "$1" | grep -Eq '^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$'; }
valid_name() { echo "$1" | grep -Eq '^[A-Za-z0-9_.@+-]+$'; }
blocked_has() { [ -f "$BLOCKED" ] && grep -qiFx "$1" "$BLOCKED"; }
rule_name() { echo "owm_block_$(echo "$1" | tr -d ':' | tr a-f A-F)"; }
firewall_reload() {
    if command -v fw4 >/dev/null 2>&1; then fw4 reload >/dev/null 2>&1
    else /etc/init.d/firewall reload >/dev/null 2>&1; fi
}

cmd_install() {
    mkdir -p "$BASE"
    touch "$BLOCKED"
    if [ "$0" != "$SELF" ]; then cp "$0" "$SELF"; fi
    chmod 700 "$SELF"
    printf '{"ok":true,"version":"%s"}\n' "$VERSION"
}

cpu_percent() {
    read_cpu() {
        set -- $(awk '/^cpu /{print $2,$3,$4,$5,$6,$7,$8,$9; exit}' /proc/stat)
        idle=$(($4 + $5))
        total=0
        for v in "$@"; do total=$((total + v)); done
        echo "$total $idle"
    }
    set -- $(read_cpu); t1="$1"; i1="$2"
    sleep 0.15
    set -- $(read_cpu); t2="$1"; i2="$2"
    dt=$((t2 - t1)); di=$((i2 - i1))
    [ "$dt" -gt 0 ] && echo $(((dt - di) * 100 / dt)) || echo 0
}

cmd_status() {
    board="$(ubus call system board 2>/dev/null || echo '{}')"
    jf() { printf '%s' "$board" | jsonfilter -e "$1" 2>/dev/null | head -n1; }
    hostname="$(jf '@.hostname')"
    model="$(jf '@.model')"
    firmware="$(jf '@.release.description')"
    arch="$(uname -m 2>/dev/null)"
    kernel="$(uname -r 2>/dev/null)"
    uptime="$(cut -d. -f1 /proc/uptime 2>/dev/null)"
    load1="$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)"
    cpu="$(cpu_percent)"
    mem_total="$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null)"
    mem_avail="$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)"
    [ -n "${mem_avail:-}" ] || mem_avail="$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)"
    dfline="$(df -kP /overlay 2>/dev/null | tail -n1)"
    [ -n "$dfline" ] || dfline="$(df -kP / 2>/dev/null | tail -n1)"
    root_total="$(echo "$dfline" | awk '{print $2}')"
    root_free="$(echo "$dfline" | awk '{print $4}')"
    temp=""
    for tf in /sys/class/thermal/thermal_zone*/temp; do
        [ -r "$tf" ] || continue
        raw="$(cat "$tf" 2>/dev/null)"
        case "$raw" in ''|*[!0-9]*) ;; *) temp="$(awk -v t="$raw" 'BEGIN{printf "%.1f", t>1000?t/1000:t}')"; break;; esac
    done
    printf '{"hostname":'; q "$hostname"; printf ',"model":'; q "$model"; printf ',"firmware":'; q "$firmware"
    printf ',"kernel":'; q "$kernel"; printf ',"arch":'; q "$arch"
    printf ',"uptime":%s,"load1":%s,"cpu_percent":%s,"mem_total_kb":%s,"mem_available_kb":%s,"root_total_kb":%s,"root_free_kb":%s,"temperature_c":' \
        "${uptime:-0}" "${load1:-0}" "${cpu:-0}" "${mem_total:-0}" "${mem_avail:-0}" "${root_total:-0}" "${root_free:-0}"
    [ -n "$temp" ] && printf '%s' "$temp" || printf 'null'
    printf '}\n'
}

wifi_details() {
    target="$(echo "$1" | tr A-F a-f)"
    for obj in $(ubus list 'hostapd.*' 2>/dev/null); do
        clients="$(ubus call "$obj" get_clients 2>/dev/null)"
        printf '%s' "$clients" | grep -qi "$target" || continue
        ifname="${obj#hostapd.}"
        channel="$(iw dev "$ifname" info 2>/dev/null | awk '/channel/{print $2; exit}')"
        case "${channel:-0}" in
            ''|*[!0-9]*) band="Wi-Fi" ;;
            *) [ "$channel" -le 14 ] && band="2.4 GHz" || band="5 GHz" ;;
        esac
        signal="$(iw dev "$ifname" station get "$1" 2>/dev/null | awk '/signal:/{print int($2); exit}')"
        printf '%s|%s|%s\n' "$ifname" "$band" "$signal"
        return 0
    done
    return 1
}

state_rank() {
    case "$1" in
        REACHABLE) echo 60 ;;
        DELAY) echo 50 ;;
        PROBE) echo 40 ;;
        STALE) echo 30 ;;
        PERMANENT) echo 20 ;;
        *) echo 10 ;;
    esac
}

cmd_devices() {
    tmp="/tmp/owm-neigh.$$"
    ip -4 neigh show 2>/dev/null | awk '$0 !~ /FAILED/ && /lladdr/ {print}' > "$tmp"
    first=1
    seen=" "
    printf '['
    while IFS= read -r line; do
        set -- $line
        ip="${1:-}"; ifname="${3:-}"; mac="${5:-}"; state="${6:-}"
        valid_mac "$mac" || continue
        mac_upper="$(echo "$mac" | tr a-f A-F)"
        case "$seen" in *" $mac_upper "*) continue ;; esac

        best="$line"; best_rank="$(state_rank "$state")"
        while IFS= read -r other; do
            echo "$other" | grep -qi "lladdr $mac " || continue
            set -- $other
            r="$(state_rank "${6:-}")"
            if [ "$r" -gt "$best_rank" ]; then best="$other"; best_rank="$r"; fi
        done < "$tmp"
        set -- $best
        ip="${1:-}"; ifname="${3:-}"; mac="${5:-}"; state="${6:-}"
        seen="$seen$mac_upper "

        hostname="$(awk -v m="$mac" 'tolower($2)==tolower(m){print $4; exit}' /tmp/dhcp.leases 2>/dev/null)"
        [ -n "$hostname" ] || hostname="$(awk -v i="$ip" '$3==i{print $4; exit}' /tmp/dhcp.leases 2>/dev/null)"
        [ "$hostname" = "*" ] && hostname=""

        type="ethernet"; band=""; signal=""
        wd="$(wifi_details "$mac" 2>/dev/null || true)"
        if [ -n "$wd" ]; then
            type="wifi"
            oldifs="$IFS"; IFS='|'; set -- $wd; IFS="$oldifs"
            [ -n "${1:-}" ] && ifname="$1"
            band="${2:-}"
            signal="${3:-}"
        fi

        blocked=false; blocked_has "$mac_upper" && blocked=true
        online=false
        if [ "$type" = "wifi" ]; then
            online=true
        else
            case "$state" in REACHABLE|DELAY|PROBE) online=true ;; esac
        fi
        [ $first -eq 1 ] || printf ','; first=0
        printf '{"ip":'; q "$ip"; printf ',"mac":'; q "$mac_upper"; printf ',"hostname":'; q "$hostname"
        printf ',"interface":'; q "$ifname"; printf ',"state":'; q "$state"; printf ',"type":'; q "$type"
        printf ',"band":'; q "$band"; printf ',"signal_dbm":'
        [ -n "$signal" ] && printf '%s' "$signal" || printf 'null'
        printf ',"blocked":%s,"online":%s}' "$blocked" "$online"
    done < "$tmp"
    rm -f "$tmp"
    printf ']\n'
}

iface_value() {
    json="$1"; expr="$2"
    printf '%s' "$json" | jsonfilter -e "$expr" 2>/dev/null | head -n1
}

print_dns_array() {
    json="$1"
    values="$(printf '%s' "$json" | jsonfilter -e '@["dns-server"][*]' 2>/dev/null)"
    first=1
    printf '['
    for d in $values; do
        [ $first -eq 1 ] || printf ','; first=0
        q "$d"
    done
    printf ']'
}

cmd_network() {
    wan="$(ubus call network.interface.wan status 2>/dev/null || echo '{}')"
    wan6="$(ubus call network.interface.wan6 status 2>/dev/null || echo '{}')"
    lan="$(ubus call network.interface.lan status 2>/dev/null || echo '{}')"
    wan_proto="$(iface_value "$wan" '@.proto')"
    wan_dev="$(iface_value "$wan" '@.l3_device')"; [ -n "$wan_dev" ] || wan_dev="$(iface_value "$wan" '@.device')"
    wan_uptime="$(iface_value "$wan" '@.uptime')"
    wan_up="$(iface_value "$wan" '@.up')"; [ "$wan_up" = "true" ] && wan_up=true || wan_up=false
    wan4="$(iface_value "$wan" '@["ipv4-address"][0].address')"
    wan6addr="$(iface_value "$wan6" '@["ipv6-address"][0].address')"
    [ -n "$wan6addr" ] || wan6addr="$(iface_value "$wan" '@["ipv6-address"][0].address')"
    gateway="$(ip route show default 2>/dev/null | awk '/default/{print $3; exit}')"
    lan_dev="$(iface_value "$lan" '@.l3_device')"; [ -n "$lan_dev" ] || lan_dev="$(iface_value "$lan" '@.device')"
    lan4="$(iface_value "$lan" '@["ipv4-address"][0].address')"
    lan6="$(iface_value "$lan" '@["ipv6-prefix-assignment"][0]["local-address"].address')"
    [ -n "$lan6" ] || lan6="$(iface_value "$lan" '@["ipv6-address"][0].address')"

    printf '{"wan_proto":'; q "$wan_proto"; printf ',"wan_device":'; q "$wan_dev"
    printf ',"wan_ipv4":'; q "$wan4"; printf ',"wan_ipv6":'; q "$wan6addr"
    printf ',"wan_uptime":%s,"wan_up":%s,"wan_gateway":' "${wan_uptime:-0}" "$wan_up"; q "$gateway"
    printf ',"wan_dns":'; print_dns_array "$wan"
    printf ',"lan_device":'; q "$lan_dev"; printf ',"lan_ipv4":'; q "$lan4"; printf ',"lan_ipv6":'; q "$lan6"; printf '}\n'
}


cmd_traffic() {
    wan="$(ubus call network.interface.wan status 2>/dev/null || echo '{}')"
    wan_dev="$(printf '%s' "$wan" | jsonfilter -e '@.l3_device' 2>/dev/null | head -n1)"
    [ -n "$wan_dev" ] || wan_dev="$(printf '%s' "$wan" | jsonfilter -e '@.device' 2>/dev/null | head -n1)"

    printf '{"wan_device":'
    q "$wan_dev"
    printf ',"interfaces":['
    first=1
    for d in /sys/class/net/*; do
        [ -d "$d" ] || continue
        name="${d##*/}"
        [ "$name" = "lo" ] && continue

        state="$(cat "$d/operstate" 2>/dev/null)"
        [ "$state" = "up" ] && up=true || up=false

        rx_bytes="$(cat "$d/statistics/rx_bytes" 2>/dev/null)"; [ -n "$rx_bytes" ] || rx_bytes=0
        tx_bytes="$(cat "$d/statistics/tx_bytes" 2>/dev/null)"; [ -n "$tx_bytes" ] || tx_bytes=0
        rx_packets="$(cat "$d/statistics/rx_packets" 2>/dev/null)"; [ -n "$rx_packets" ] || rx_packets=0
        tx_packets="$(cat "$d/statistics/tx_packets" 2>/dev/null)"; [ -n "$tx_packets" ] || tx_packets=0
        rx_errors="$(cat "$d/statistics/rx_errors" 2>/dev/null)"; [ -n "$rx_errors" ] || rx_errors=0
        tx_errors="$(cat "$d/statistics/tx_errors" 2>/dev/null)"; [ -n "$tx_errors" ] || tx_errors=0
        rx_dropped="$(cat "$d/statistics/rx_dropped" 2>/dev/null)"; [ -n "$rx_dropped" ] || rx_dropped=0
        tx_dropped="$(cat "$d/statistics/tx_dropped" 2>/dev/null)"; [ -n "$tx_dropped" ] || tx_dropped=0

        [ $first -eq 1 ] || printf ','
        first=0
        printf '{"name":'; q "$name"
        printf ',"up":%s,"rx_bytes":%s,"tx_bytes":%s' "$up" "$rx_bytes" "$tx_bytes"
        printf ',"rx_packets":%s,"tx_packets":%s' "$rx_packets" "$tx_packets"
        printf ',"rx_errors":%s,"tx_errors":%s' "$rx_errors" "$tx_errors"
        printf ',"rx_dropped":%s,"tx_dropped":%s}' "$rx_dropped" "$tx_dropped"
    done
    printf ']}\n'
}

diag_item() {
    id="$1"; title="$2"; status="$3"; detail="$4"
    [ "${diag_first:-1}" = "1" ] || printf ','
    diag_first=0
    printf '{"id":'; q "$id"
    printf ',"title":'; q "$title"
    printf ',"status":'; q "$status"
    printf ',"detail":'; q "$detail"
    printf '}'
}

cmd_diagnostics() {
    wan="$(ubus call network.interface.wan status 2>/dev/null || echo '{}')"
    wan_up="$(printf '%s' "$wan" | jsonfilter -e '@.up' 2>/dev/null | head -n1)"
    gateway="$(ip route show default 2>/dev/null | awk '/default/{print $3; exit}')"

    printf '{"checks":['
    diag_first=1

    if [ "$wan_up" = "true" ]; then
        diag_item "wan" "WAN 接口" "ok" "WAN 接口已连接"
    else
        diag_item "wan" "WAN 接口" "fail" "WAN 接口未连接"
    fi

    if [ -n "$gateway" ]; then
        diag_item "route" "默认路由" "ok" "已检测到默认网关"
        if ping -c 1 -W 2 "$gateway" >/dev/null 2>&1; then
            diag_item "gateway" "上联网关" "ok" "默认网关可达"
        else
            diag_item "gateway" "上联网关" "fail" "默认网关无响应"
        fi
    else
        diag_item "route" "默认路由" "fail" "没有默认路由"
        diag_item "gateway" "上联网关" "warn" "无法测试：没有默认网关"
    fi

    if ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1; then
        diag_item "internet" "互联网连通" "ok" "公网 IP 连通正常"
    else
        diag_item "internet" "互联网连通" "fail" "公网 IP 测试失败"
    fi

    if command -v nslookup >/dev/null 2>&1; then
        if nslookup openwrt.org 127.0.0.1 >/dev/null 2>&1; then
            diag_item "dns" "DNS 解析" "ok" "本机 DNS 解析正常"
        else
            diag_item "dns" "DNS 解析" "fail" "本机 DNS 解析失败"
        fi
    else
        diag_item "dns" "DNS 解析" "warn" "系统没有 nslookup，跳过测试"
    fi

    if [ -f /etc/openwrt-manager/safe-apply/active ]; then
        diag_item "safe_apply" "配置事务" "warn" "存在等待确认的 Safe Apply"
    else
        diag_item "safe_apply" "配置事务" "ok" "没有待确认配置事务"
    fi

    printf ']}\n'
}

uci_get() { uci -q get "$1" 2>/dev/null || true; }

wifi_runtime_ifname() {
    dev="$1"
    ubus call network.wireless status 2>/dev/null | jsonfilter -e "@.$dev.interfaces[0].ifname" 2>/dev/null | head -n1
}

wifi_client_count() {
    ifname="$1"
    [ -n "$ifname" ] || { echo 0; return; }
    ubus call "hostapd.$ifname" get_clients 2>/dev/null \
        | grep -Ec '"[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"[[:space:]]*:' || echo 0
}

cmd_wifi() {
    first=1; printf '['
    for sec in $(uci -q show wireless 2>/dev/null | sed -n "s/^wireless\.\([^.=]*\)=wifi-device$/\1/p"); do
        [ $first -eq 1 ] || printf ','; first=0
        disabled="$(uci_get wireless.$sec.disabled)"; [ "$disabled" = "1" ] && dis=true || dis=false
        printf '{"section":'; q "$sec"; printf ',"kind":"radio","channel":'; q "$(uci_get wireless.$sec.channel)"
        printf ',"band":'; q "$(uci_get wireless.$sec.band)"; printf ',"htmode":'; q "$(uci_get wireless.$sec.htmode)"
        printf ',"disabled":%s}' "$dis"
    done
    for sec in $(uci -q show wireless 2>/dev/null | sed -n "s/^wireless\.\([^.=]*\)=wifi-iface$/\1/p"); do
        [ $first -eq 1 ] || printf ','; first=0
        disabled="$(uci_get wireless.$sec.disabled)"; [ "$disabled" = "1" ] && dis=true || dis=false
        dev="$(uci_get wireless.$sec.device)"
        ifname="$(wifi_runtime_ifname "$dev")"
        count="$(wifi_client_count "$ifname")"
        printf '{"section":'; q "$sec"; printf ',"kind":"iface","device":'; q "$dev"
        printf ',"ifname":'; q "$ifname"; printf ',"ssid":'; q "$(uci_get wireless.$sec.ssid)"
        printf ',"encryption":'; q "$(uci_get wireless.$sec.encryption)"
        printf ',"client_count":%s,"disabled":%s}' "${count:-0}" "$dis"
    done
    printf ']\n'
}

cmd_services() {
    first=1; printf '['
    for f in /etc/init.d/*; do
        [ -x "$f" ] || continue
        name="${f##*/}"; valid_name "$name" || continue
        "$f" enabled >/dev/null 2>&1 && enabled=true || enabled=false
        running=false
        if ubus call service list "{\"name\":\"$name\"}" 2>/dev/null | grep -q '"running"[[:space:]]*:[[:space:]]*true'; then running=true
        elif "$f" status >/dev/null 2>&1; then running=true; fi
        case "$name" in network|firewall|dropbear|dnsmasq|odhcpd|ubus|rpcd|uhttpd) protected=true ;; *) protected=false ;; esac
        [ $first -eq 1 ] || printf ','; first=0
        printf '{"name":'; q "$name"; printf ',"enabled":%s,"running":%s,"protected":%s}' "$enabled" "$running" "$protected"
    done
    printf ']\n'
}


service_enabled() {
    svc="$1"
    [ -x "/etc/init.d/$svc" ] && "/etc/init.d/$svc" enabled >/dev/null 2>&1
}

service_running() {
    svc="$1"
    [ -x "/etc/init.d/$svc" ] || return 1
    if ubus call service list "{\"name\":\"$svc\"}" 2>/dev/null | grep -q '"running"[[:space:]]*:[[:space:]]*true'; then
        return 0
    fi
    "/etc/init.d/$svc" status >/dev/null 2>&1
}

first_pid_for() {
    pattern="$1"
    for d in /proc/[0-9]*; do
        [ -r "$d/cmdline" ] || continue
        cmd="$(tr '\000' ' ' < "$d/cmdline" 2>/dev/null)"
        echo "$cmd" | grep -qi "$pattern" || continue
        echo "${d##*/}"
        return 0
    done
    return 1
}

pid_rss_kb() {
    pid="$1"
    awk '/^VmRSS:/{print $2; exit}' "/proc/$pid/status" 2>/dev/null
}

pid_name() {
    pid="$1"
    cat "/proc/$pid/comm" 2>/dev/null | head -n1
}

pid_cmd() {
    pid="$1"
    tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null | sed 's/[[:space:]]*$//'
}

pid_user() {
    pid="$1"
    uid="$(awk '/^Uid:/{print $2; exit}' "/proc/$pid/status" 2>/dev/null)"
    [ -n "$uid" ] || { printf ''; return; }
    awk -F: -v u="$uid" '$3==u{print $1; exit}' /etc/passwd 2>/dev/null
}

is_protected_process() {
    name="$1"
    case "$name" in
        init|procd|ubusd|netifd|dnsmasq|odhcpd|dropbear|uhttpd|firewall|fw4|logd|rpcd) return 0 ;;
        *) return 1 ;;
    esac
}

cmd_app_services() {
    first=1
    printf '['
    emit_app_service() {
        id="$1"; label="$2"; svc="$3"; pattern="$4"; detail="$5"
        [ -x "/etc/init.d/$svc" ] || command -v "$pattern" >/dev/null 2>&1 || first_pid_for "$pattern" >/dev/null 2>&1 || return 0

        pid="$(first_pid_for "$pattern" 2>/dev/null || true)"
        if service_running "$svc" || [ -n "$pid" ]; then running=true; else running=false; fi
        service_enabled "$svc" && enabled=true || enabled=false
        [ -x "/etc/init.d/$svc" ] && controllable=true || controllable=false
        rss=""
        [ -n "$pid" ] && rss="$(pid_rss_kb "$pid")"
        [ "$running" = "true" ] && health="healthy" || health="stopped"

        [ $first -eq 1 ] || printf ','
        first=0
        printf '{"id":'; q "$id"
        printf ',"display_name":'; q "$label"
        printf ',"init_service":'; q "$svc"
        printf ',"controllable":%s,"running":%s,"enabled":%s' "$controllable" "$running" "$enabled"
        printf ',"health":'; q "$health"
        printf ',"version":""'
        printf ',"pid":'
        [ -n "$pid" ] && printf '%s' "$pid" || printf 'null'
        printf ',"cpu_percent":null'
        printf ',"memory_kb":'
        [ -n "$rss" ] && printf '%s' "$rss" || printf 'null'
        printf ',"ports":[]'
        printf ',"detail":'; q "$detail"
        printf '}'
    }

    emit_app_service "openclash" "OpenClash" "openclash" "clash" "代理服务"
    emit_app_service "nikki" "Nikki" "nikki" "mihomo" "代理服务"
    emit_app_service "passwall" "PassWall" "passwall" "sing-box" "代理服务"
    emit_app_service "passwall2" "PassWall2" "passwall2" "sing-box" "代理服务"
    emit_app_service "homeproxy" "HomeProxy" "homeproxy" "sing-box" "代理服务"
    emit_app_service "mihomo" "Mihomo" "mihomo" "mihomo" "代理核心"
    emit_app_service "singbox" "sing-box" "sing-box" "sing-box" "代理核心"
    emit_app_service "tailscale" "Tailscale" "tailscale" "tailscaled" "组网服务"
    emit_app_service "zerotier" "ZeroTier" "zerotier" "zerotier-one" "组网服务"
    emit_app_service "adguardhome" "AdGuard Home" "AdGuardHome" "AdGuardHome" "DNS / 广告过滤"
    emit_app_service "smartdns" "SmartDNS" "smartdns" "smartdns" "DNS 服务"
    emit_app_service "mosdns" "MosDNS" "mosdns" "mosdns" "DNS 服务"
    emit_app_service "docker" "Docker" "dockerd" "dockerd" "容器服务"
    emit_app_service "samba4" "Samba" "samba4" "smbd" "文件共享"
    emit_app_service "samba" "Samba" "samba" "smbd" "文件共享"
    emit_app_service "ddns" "DDNS" "ddns" "ddns" "动态域名"

    printf ']\n'
}

cmd_processes() {
    total_kb="$(awk '/^MemTotal:/{print $2; exit}' /proc/meminfo 2>/dev/null)"
    [ -n "$total_kb" ] || total_kb=1

    first=1
    printf '['
    count=0
    for d in /proc/[0-9]*; do
        [ -r "$d/status" ] || continue
        pid="${d##*/}"
        name="$(pid_name "$pid")"
        [ -n "$name" ] || continue
        user="$(pid_user "$pid")"
        rss="$(pid_rss_kb "$pid")"; [ -n "$rss" ] || rss=0
        cmd="$(pid_cmd "$pid")"
        is_protected_process "$name" && protected=true || protected=false
        mempct="$(awk -v r="$rss" -v t="$total_kb" 'BEGIN{printf "%.2f", (t>0?r*100/t:0)}')"

        [ $first -eq 1 ] || printf ','
        first=0
        printf '{"pid":%s,"name":' "$pid"; q "$name"
        printf ',"user":'; q "$user"
        printf ',"cpu_percent":0.0,"memory_percent":%s,"rss_kb":%s' "$mempct" "$rss"
        printf ',"command":'; q "$cmd"
        printf ',"protected":%s}' "$protected"

        count=$((count + 1))
        [ "$count" -ge 160 ] && break
    done
    printf ']\n'
}

cmd_service_logs() {
    name="${1:-}"
    lines="${2:-120}"
    valid_name "$name" || exit 2
    case "$lines" in *[!0-9]*) lines=120;; esac
    [ "$lines" -gt 300 ] && lines=300
    logread 2>/dev/null | grep -i "$name" | tail -n "$lines"
}


valid_pkg() {
    echo "$1" | grep -Eq '^[A-Za-z0-9_.+@-]+$'
}

pkg_protected() {
    case "$1" in
        base-files|busybox|libc|kernel|procd|ubus|ubusd|uci|netifd|firewall4|fw4|opkg|dropbear|dnsmasq|odhcpd|rpcd|uhttpd) return 0 ;;
        kmod-*|libubus*|libuci*|libubox*) return 0 ;;
        *) return 1 ;;
    esac
}

cmd_pkg_status() {
    command -v opkg >/dev/null 2>&1 || {
        echo '{"manager":"","installed_count":0,"upgradable_count":0,"overlay_free_kb":0}'
        return
    }
    installed="$(opkg list-installed 2>/dev/null | wc -l | tr -d ' ')"
    upgradable="$(opkg list-upgradable 2>/dev/null | wc -l | tr -d ' ')"
    dfline="$(df -kP /overlay 2>/dev/null | tail -n1)"
    [ -n "$dfline" ] || dfline="$(df -kP / 2>/dev/null | tail -n1)"
    free="$(echo "$dfline" | awk '{print $4}')"
    printf '{"manager":"opkg","installed_count":%s,"upgradable_count":%s,"overlay_free_kb":%s}\n' \
        "${installed:-0}" "${upgradable:-0}" "${free:-0}"
}

emit_pkg() {
    name="$1"; version="$2"; available="$3"; desc="$4"; installed="$5"; upgradable="$6"
    pkg_protected "$name" && protected=true || protected=false
    printf '{"name":'; q "$name"
    printf ',"version":'; q "$version"
    printf ',"available_version":'; q "$available"
    printf ',"description":'; q "$desc"
    printf ',"installed":%s,"upgradable":%s,"protected":%s}' "$installed" "$upgradable" "$protected"
}

cmd_pkg_list_installed() {
    first=1
    printf '['
    opkg list-installed 2>/dev/null | while IFS= read -r line; do
        name="$(printf '%s\n' "$line" | awk -F ' - ' '{print $1}')"
        version="$(printf '%s\n' "$line" | awk -F ' - ' '{print $2}')"
        [ -n "$name" ] || continue
        [ $first -eq 1 ] || printf ','
        first=0
        emit_pkg "$name" "$version" "" "" true false
    done
    printf ']\n'
}

cmd_pkg_list_upgradable() {
    first=1
    printf '['
    opkg list-upgradable 2>/dev/null | while IFS= read -r line; do
        name="$(printf '%s\n' "$line" | awk -F ' - ' '{print $1}')"
        old="$(printf '%s\n' "$line" | awk -F ' - ' '{print $2}')"
        new="$(printf '%s\n' "$line" | awk -F ' - ' '{print $3}')"
        [ -n "$name" ] || continue
        [ $first -eq 1 ] || printf ','
        first=0
        emit_pkg "$name" "$old" "$new" "" true true
    done
    printf ']\n'
}

cmd_pkg_search() {
    query="${1:-}"
    echo "$query" | grep -Eq '^[A-Za-z0-9_.+@-]{2,64}$' || {
        echo '[]'
        return
    }

    first=1
    count=0
    printf '['
    opkg list 2>/dev/null | awk -v q="$query" 'index(tolower($0),tolower(q))>0 {print; n++; if(n>=80) exit}' | \
    while IFS= read -r line; do
        name="$(printf '%s\n' "$line" | awk -F ' - ' '{print $1}')"
        version="$(printf '%s\n' "$line" | awk -F ' - ' '{print $2}')"
        desc="$(printf '%s\n' "$line" | cut -d'-' -f3- | sed 's/^ //')"
        [ -n "$name" ] || continue
        installed=false
        installed_ver=""
        if opkg status "$name" 2>/dev/null | grep -q '^Status: .* installed'; then
            installed=true
            installed_ver="$(opkg status "$name" 2>/dev/null | awk -F': ' '/^Version:/{print $2; exit}')"
        fi
        [ $first -eq 1 ] || printf ','
        first=0
        emit_pkg "$name" "$installed_ver" "$version" "$desc" "$installed" false
        count=$((count + 1))
        [ "$count" -ge 80 ] && break
    done
    printf ']\n'
}

cmd_pkg_update() {
    command -v opkg >/dev/null 2>&1 || { echo '{"ok":false,"error":"opkg not found"}' >&2; exit 2; }
    if opkg update >/tmp/owm-opkg-update.log 2>&1; then
        echo '{"ok":true}'
    else
        tail -n 30 /tmp/owm-opkg-update.log >&2
        exit 3
    fi
}

cmd_pkg_install() {
    pkg="${1:-}"
    valid_pkg "$pkg" || { echo '{"ok":false,"error":"invalid package"}' >&2; exit 2; }
    if opkg install "$pkg" >/tmp/owm-opkg-action.log 2>&1; then
        printf '{"ok":true,"package":'; q "$pkg"; printf '}\n'
    else
        tail -n 40 /tmp/owm-opkg-action.log >&2
        exit 3
    fi
}

cmd_pkg_upgrade() {
    pkg="${1:-}"
    valid_pkg "$pkg" || { echo '{"ok":false,"error":"invalid package"}' >&2; exit 2; }
    pkg_protected "$pkg" && { echo '{"ok":false,"error":"protected package"}' >&2; exit 4; }
    if opkg upgrade "$pkg" >/tmp/owm-opkg-action.log 2>&1; then
        printf '{"ok":true,"package":'; q "$pkg"; printf '}\n'
    else
        tail -n 40 /tmp/owm-opkg-action.log >&2
        exit 3
    fi
}

cmd_pkg_remove() {
    pkg="${1:-}"
    valid_pkg "$pkg" || { echo '{"ok":false,"error":"invalid package"}' >&2; exit 2; }
    pkg_protected "$pkg" && { echo '{"ok":false,"error":"protected package"}' >&2; exit 4; }
    if opkg status "$pkg" 2>/dev/null | grep -qi '^Essential: yes'; then
        echo '{"ok":false,"error":"essential package"}' >&2
        exit 5
    fi
    if opkg remove "$pkg" >/tmp/owm-opkg-action.log 2>&1; then
        printf '{"ok":true,"package":'; q "$pkg"; printf '}\n'
    else
        tail -n 40 /tmp/owm-opkg-action.log >&2
        exit 3
    fi
}

cmd_block() {
    mac="$(echo "$1" | tr a-f A-F)"; valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }
    sec="$(rule_name "$mac")"
    mkdir -p "$BASE"; touch "$BLOCKED"
    blocked_has "$mac" || echo "$mac" >> "$BLOCKED"
    sort -u "$BLOCKED" -o "$BLOCKED" 2>/dev/null || true
    uci -q delete "firewall.$sec" || true
    uci set "firewall.$sec=rule"
    uci set "firewall.$sec.name=OpenWrt Manager block $mac"
    uci set "firewall.$sec.src=lan"
    uci set "firewall.$sec.dest=wan"
    uci set "firewall.$sec.src_mac=$mac"
    uci set "firewall.$sec.target=REJECT"
    uci set "firewall.$sec.enabled=1"
    uci commit firewall
    firewall_reload || true
    echo '{"ok":true}'
}

cmd_unblock() {
    mac="$(echo "$1" | tr a-f A-F)"; valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }
    sec="$(rule_name "$mac")"
    if [ -f "$BLOCKED" ]; then
        grep -viFx "$mac" "$BLOCKED" > "$BLOCKED.tmp" || true
        mv "$BLOCKED.tmp" "$BLOCKED"
    fi
    uci -q delete "firewall.$sec" || true
    uci commit firewall
    firewall_reload || true
    echo '{"ok":true}'
}

cmd_service() {
    name="${1:-}"; action="${2:-}"; valid_name "$name" || exit 2
    case "$action" in start|stop|restart|reload|enable|disable) ;; *) exit 2;; esac
    [ -x "/etc/init.d/$name" ] || exit 3

    case "$name" in
        network|firewall|dropbear|dnsmasq|odhcpd|ubus|rpcd|uhttpd)
            case "$action" in
                stop|restart|reload|disable)
                    echo '{"ok":false,"error":"protected service"}'
                    exit 4
                    ;;
            esac
            ;;
    esac

    "/etc/init.d/$name" "$action" >/dev/null 2>&1
    printf '{"ok":true}\n'
}

case "${1:-}" in
    install) cmd_install ;;
    version) printf '{"version":"%s","protocol":1}\n' "$VERSION" ;;
    status) cmd_status ;;
    devices) cmd_devices ;;
    network) cmd_network ;;
    traffic) cmd_traffic ;;
    diagnostics) cmd_diagnostics ;;
    wifi) cmd_wifi ;;
    services) cmd_services ;;
    app-services) cmd_app_services ;;
    processes) cmd_processes ;;
    service-logs) cmd_service_logs "${2:-}" "${3:-120}" ;;
    pkg-status) cmd_pkg_status ;;
    pkg-installed) cmd_pkg_list_installed ;;
    pkg-upgradable) cmd_pkg_list_upgradable ;;
    pkg-search) cmd_pkg_search "${2:-}" ;;
    pkg-update) cmd_pkg_update ;;
    pkg-install) cmd_pkg_install "${2:-}" ;;
    pkg-upgrade) cmd_pkg_upgrade "${2:-}" ;;
    pkg-remove) cmd_pkg_remove "${2:-}" ;;
    logs) lines="${2:-200}"; case "$lines" in *[!0-9]*) lines=200;; esac; logread -l "$lines" 2>/dev/null || logread 2>/dev/null | tail -n "$lines" ;;
    block) cmd_block "${2:-}" ;;
    unblock) cmd_unblock "${2:-}" ;;
    service) cmd_service "${2:-}" "${3:-}" ;;
    network-restart) echo '{"ok":true}'; /etc/init.d/network restart >/dev/null 2>&1 & ;;
    reboot) echo '{"ok":true}'; sync; (sleep 1; reboot) >/dev/null 2>&1 & ;;
    *) echo '{"error":"unknown command"}'; exit 2 ;;
esac
