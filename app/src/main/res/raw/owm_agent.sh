#!/bin/sh
# OpenWrt Manager Agent V0.2.1
# Command agent used over SSH. It opens no listening socket.
set -u
VERSION="0.2.1"
BASE="/etc/openwrt-manager"
BLOCKED="$BASE/blocked_macs"
SCHEDULE_BLOCKED="$BASE/scheduled_blocked_macs"
ALIASES="$BASE/device_aliases.tsv"
SELF="/usr/bin/owm-agent"

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r/\\r/g'
}
q() { printf '"%s"' "$(json_escape "${1:-}")"; }
valid_mac() { echo "$1" | grep -Eq '^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$'; }
valid_name() { echo "$1" | grep -Eq '^[A-Za-z0-9_.@+-]+$'; }
blocked_has() { [ -f "$BLOCKED" ] && grep -qiFx "$1" "$BLOCKED"; }
scheduled_blocked_has() { [ -f "$SCHEDULE_BLOCKED" ] && grep -qiFx "$1" "$SCHEDULE_BLOCKED"; }
effective_blocked() { blocked_has "$1" || scheduled_blocked_has "$1"; }
device_alias() {
    [ -f "$ALIASES" ] || return 0
    encoded="$(awk -F '\t' -v m="$1" 'toupper($1)==toupper(m){print $2; exit}' "$ALIASES" 2>/dev/null)"
    [ -n "$encoded" ] || return 0
    printf '%s' "$encoded" | base64 -d 2>/dev/null || true
}
rule_name() { echo "owm_block_$(echo "$1" | tr -d ':' | tr a-f A-F)"; }
firewall_reload() {
    if command -v fw4 >/dev/null 2>&1; then fw4 reload >/dev/null 2>&1
    else /etc/init.d/firewall reload >/dev/null 2>&1; fi
}

cmd_install() {
    mkdir -p "$BASE"
    touch "$BLOCKED" "$SCHEDULE_BLOCKED" "$ALIASES" /etc/sysupgrade.conf
    grep -qxF '/etc/openwrt-manager/' /etc/sysupgrade.conf 2>/dev/null || echo '/etc/openwrt-manager/' >> /etc/sysupgrade.conf
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


neigh_ip() {
    printf '%s\n' "$1" | awk '{print $1}'
}

neigh_dev() {
    printf '%s\n' "$1" | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}'
}

neigh_mac() {
    printf '%s\n' "$1" | awk '{for(i=1;i<=NF;i++) if($i=="lladdr"){print $(i+1); exit}}'
}

neigh_state() {
    printf '%s\n' "$1" | awk '{
        for(i=1;i<=NF;i++) {
            if($i ~ /^(REACHABLE|STALE|DELAY|PROBE|FAILED|NOARP|PERMANENT|INCOMPLETE)$/) {
                print $i
                exit
            }
        }
    }'
}

cmd_devices() {
    tmp="/tmp/owm-neigh.$$"
    alltmp="/tmp/owm-neigh-all.$$"
    ip neigh show 2>/dev/null | awk '$0 !~ /FAILED/ && /lladdr/ {print}' > "$alltmp"
    cp "$alltmp" "$tmp"
    first=1
    seen=" "
    printf '['
    while IFS= read -r line; do
        ip="$(neigh_ip "$line")"
        ifname="$(neigh_dev "$line")"
        mac="$(neigh_mac "$line")"
        state="$(neigh_state "$line")"
        valid_mac "$mac" || continue
        mac_upper="$(echo "$mac" | tr a-f A-F)"
        case "$seen" in *" $mac_upper "*) continue ;; esac

        best="$line"; best_rank="$(state_rank "$state")"
        case "$ip" in *:*) ;; *) best_rank=$((best_rank + 5)) ;; esac
        while IFS= read -r other; do
            echo "$other" | grep -qi "lladdr $mac " || continue
            candidate_ip="$(neigh_ip "$other")"
            candidate_state="$(neigh_state "$other")"
            r="$(state_rank "$candidate_state")"
            case "$candidate_ip" in *:*) ;; *) r=$((r + 5)) ;; esac
            if [ "$r" -gt "$best_rank" ]; then best="$other"; best_rank="$r"; fi
        done < "$tmp"
        ip="$(neigh_ip "$best")"
        ifname="$(neigh_dev "$best")"
        mac="$(neigh_mac "$best")"
        state="$(neigh_state "$best")"
        seen="$seen$mac_upper "

        hostname="$(device_alias "$mac_upper")"
        [ -n "$hostname" ] || hostname="$(awk -v m="$mac" 'tolower($2)==tolower(m){print $4; exit}' /tmp/dhcp.leases 2>/dev/null)"
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

        blocked=false; effective_blocked "$mac_upper" && blocked=true
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
        printf ',"blocked":%s,"online":%s,"addresses":[' "$blocked" "$online"
        addr_first=1
        addr_seen=" "
        while IFS= read -r other; do
            echo "$other" | grep -qi "lladdr $mac " || continue
            addr="$(neigh_ip "$other")"
            [ -n "$addr" ] || continue
            case "$addr_seen" in *" $addr "*) continue ;; esac
            addr_seen="$addr_seen$addr "
            [ "$addr_first" = "1" ] || printf ','
            addr_first=0
            q "$addr"
        done < "$alltmp"
        printf ']}'
    done < "$tmp"
    rm -f "$tmp" "$alltmp"
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



cmd_device_traffic_capability() {
    installed=false
    running=false
    available=false
    has_data=false
    detail="未安装 nlbwmon"

    if command -v nlbw >/dev/null 2>&1; then
        installed=true
        detail="已安装 nlbwmon"

        if [ -x /etc/init.d/nlbwmon ] && /etc/init.d/nlbwmon status >/dev/null 2>&1; then
            running=true
        elif pidof nlbwmon >/dev/null 2>&1; then
            running=true
        fi

        out="$(nlbw -c json -g mac -o mac 2>/tmp/owm-nlbw-error.$$ || true)"
        if printf '%s' "$out" | grep -q '"columns"'; then
            available=true
            first_mac="$(printf '%s' "$out" | jsonfilter -e '@.data[0][0]' 2>/dev/null | head -n1)"
            if [ -n "$first_mac" ]; then
                has_data=true
                detail="nlbwmon 统计可用"
            else
                detail="nlbwmon 已运行，暂时没有流量数据"
            fi
        else
            detail="nlbwmon 查询失败"
        fi
        rm -f /tmp/owm-nlbw-error.$$
    fi

    printf '{"installed":%s,"running":%s,"available":%s,"has_data":%s,"backend":"nlbwmon","detail":' \
        "$installed" "$running" "$available" "$has_data"
    q "$detail"
    printf '}\n'
}

cmd_device_traffic() {
    if ! command -v nlbw >/dev/null 2>&1; then
        echo '{"columns":["mac","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts"],"data":[]}'
        return
    fi

    if ! nlbw -c json -g mac -o mac 2>/tmp/owm-nlbw-error.$$; then
        rm -f /tmp/owm-nlbw-error.$$
        echo '{"columns":["mac","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts"],"data":[]}'
        return
    fi
    rm -f /tmp/owm-nlbw-error.$$
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

luci_slug_exists() {
    slug="$1"
    for file in /usr/share/luci/menu.d/*.json; do
        [ -f "$file" ] || continue
        grep -Fq "\"admin/services/$slug\"" "$file" 2>/dev/null && return 0
    done
    return 1
}

luci_title_for_slug() {
    slug="$1"
    case "$slug" in
        homeproxy) printf '%s' "HomeProxy"; return ;;
        mosdns) printf '%s' "MosDNS"; return ;;
        nikki) printf '%s' "Nikki"; return ;;
        ddns-go|ddns_go) printf '%s' "DDNS-Go"; return ;;
        wol|wakeonlan|wake-on-lan) printf '%s' "Wake on LAN"; return ;;
        nezha|nezha-agent|nezha_agent) printf '%s' "Nezha Agent"; return ;;
        upnp|miniupnpd) printf '%s' "UPnP IGD 和 PCP"; return ;;
        openclash) printf '%s' "OpenClash"; return ;;
        passwall) printf '%s' "PassWall"; return ;;
        passwall2) printf '%s' "PassWall2"; return ;;
        adguardhome) printf '%s' "AdGuard Home"; return ;;
        smartdns) printf '%s' "SmartDNS"; return ;;
    esac

    if command -v jsonfilter >/dev/null 2>&1; then
        for file in /usr/share/luci/menu.d/*.json; do
            [ -f "$file" ] || continue
            grep -Fq "\"admin/services/$slug\"" "$file" 2>/dev/null || continue
            title="$(jsonfilter -i "$file" -e "@[\\\"admin/services/$slug\\\"].title" 2>/dev/null | head -n1)"
            if [ -n "$title" ]; then
                printf '%s' "$title"
                return
            fi
        done
    fi

    printf '%s' "$slug"
}

luci_endpoint() {
    scheme="http"
    port="80"

    https_list="$(uci -q get uhttpd.main.listen_https 2>/dev/null)"
    if [ -n "$https_list" ]; then
        for addr in $https_list; do
            p="${addr##*:}"
            p="$(printf '%s' "$p" | tr -cd '0-9')"
            if [ -n "$p" ]; then
                scheme="https"
                port="$p"
                printf '%s %s' "$scheme" "$port"
                return
            fi
        done
    fi

    http_list="$(uci -q get uhttpd.main.listen_http 2>/dev/null)"
    if [ -n "$http_list" ]; then
        for addr in $http_list; do
            p="${addr##*:}"
            p="$(printf '%s' "$p" | tr -cd '0-9')"
            if [ -n "$p" ]; then
                port="$p"
                break
            fi
        done
    fi

    printf '%s %s' "$scheme" "$port"
}

service_installed_evidence() {
    id="$1"; svc="$2"; slug="$3"; pkg_hint="$4"
    [ -n "$svc" ] && [ -x "/etc/init.d/$svc" ] && return 0
    [ -f "/etc/config/$id" ] && return 0
    [ -n "$svc" ] && [ -f "/etc/config/$svc" ] && return 0
    [ -n "$slug" ] && luci_slug_exists "$slug" && return 0
    [ -n "$pkg_hint" ] && opkg status "$pkg_hint" 2>/dev/null | grep -q '^Status: .* installed' && return 0
    opkg status "luci-app-$id" 2>/dev/null | grep -q '^Status: .* installed' && return 0
    opkg status "$id" 2>/dev/null | grep -q '^Status: .* installed' && return 0
    return 1
}

installed_package_for() {
    id="$1"; svc="$2"; pkg_hint="$3"
    for pkg in "$pkg_hint" "luci-app-$id" "$id" "$svc"; do
        [ -n "$pkg" ] || continue
        if opkg status "$pkg" 2>/dev/null | grep -q '^Status: .* installed'; then
            printf '%s' "$pkg"
            return
        fi
    done
}

package_version() {
    pkg="$1"
    [ -n "$pkg" ] || return 0
    opkg status "$pkg" 2>/dev/null | awk -F': ' '/^Version:/{print $2; exit}'
}

service_pid() {
    svc="$1"
    [ -n "$svc" ] || return 1
    out="$(ubus call service list "{\"name\":\"$svc\"}" 2>/dev/null)"
    pid="$(printf '%s\n' "$out" | sed -n 's/.*"pid":[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p' | head -n1)"
    [ -n "$pid" ] || return 1
    printf '%s' "$pid"
}

first_pid_for_app() {
    id="$1"; svc="$2"; pattern="$3"

    pid="$(service_pid "$svc" 2>/dev/null || true)"
    [ -n "$pid" ] && { printf '%s' "$pid"; return 0; }

    for d in /proc/[0-9]*; do
        [ -r "$d/cmdline" ] || continue
        cmd="$(tr '\000' ' ' < "$d/cmdline" 2>/dev/null)"
        [ -n "$cmd" ] || continue
        if { [ -n "$id" ] && printf '%s' "$cmd" | grep -qi "$id"; } || \
           { [ -n "$svc" ] && printf '%s' "$cmd" | grep -qi "$svc"; }; then
            printf '%s' "${d##*/}"
            return 0
        fi
    done

    case "$pattern" in
        sing-box|mihomo|clash) return 1 ;;
    esac
    [ -n "$pattern" ] && first_pid_for "$pattern" 2>/dev/null
}

pid_uptime_seconds() {
    pid="$1"
    [ -r "/proc/$pid/stat" ] || return 0
    total="$(awk '{print int($1)}' /proc/uptime 2>/dev/null)"
    stat="$(cat "/proc/$pid/stat" 2>/dev/null)"
    rest="${stat#*) }"
    start_ticks="$(printf '%s\n' "$rest" | awk '{print $20}')"
    clk="$(getconf CLK_TCK 2>/dev/null)"
    [ -n "$clk" ] || clk=100
    case "$start_ticks:$clk:$total" in
        *[!0-9:]*|::*|*::*) return 0 ;;
    esac
    start_seconds=$((start_ticks / clk))
    [ "$total" -ge "$start_seconds" ] && echo $((total - start_seconds))
}

listen_ports_for_pid() {
    pid="$1"
    [ -n "$pid" ] || return 0

    if command -v ss >/dev/null 2>&1; then
        ss -lntp 2>/dev/null \
            | grep "pid=$pid," \
            | awk '{print $4}' \
            | sed 's/.*://' \
            | tr -cd '0-9\n' \
            | awk '$1>=1 && $1<=65535 {print $1}' \
            | sort -nu
        return
    fi

    if command -v netstat >/dev/null 2>&1; then
        netstat -lntp 2>/dev/null \
            | awk -v p="/$pid" '$0 ~ p {print $4}' \
            | sed 's/.*://' \
            | tr -cd '0-9\n' \
            | awk '$1>=1 && $1<=65535 {print $1}' \
            | sort -nu
    fi
}

http_scheme_for_port() {
    port="$1"
    case "$port" in
        443|8443|9443) printf '%s' "https"; return ;;
    esac

    if command -v nc >/dev/null 2>&1; then
        first="$(printf 'HEAD / HTTP/1.0\r\nHost: localhost\r\n\r\n' \
            | nc -w 1 127.0.0.1 "$port" 2>/dev/null \
            | head -n1)"
        printf '%s' "$first" | grep -q '^HTTP/' && {
            printf '%s' "http"
            return
        }
    fi

    case "$port" in
        80|3000|8080|8000|8888|9090|9876) printf '%s' "http" ;;
        *) printf '%s' "" ;;
    esac
}

standalone_panel_for() {
    id="$1"; ports="$2"
    preferred=""

    case "$id" in
        ddns-go) preferred="9876" ;;
        adguardhome) preferred="3000 80 8080" ;;
    esac

    for p in $preferred $ports; do
        case " $ports " in *" $p "*) ;; *) continue ;; esac
        scheme="$(http_scheme_for_port "$p")"
        [ -n "$scheme" ] || continue
        printf '%s %s' "$scheme" "$p"
        return
    done
}

system_service_name() {
    case "$1" in
        boot|cron|dnsmasq|done|dropbear|firewall|gpio_switch|led|log|network|odhcpd|rpcd|sysctl|sysfixtime|sysntpd|system|uhttpd|umount|urandom_seed|urngd|ubus|wan|watchcat)
            return 0 ;;
        *) return 1 ;;
    esac
}

cmd_app_services() {
    set -- $(luci_endpoint)
    luci_scheme="${1:-http}"
    luci_port="${2:-80}"
    seen="|"
    seen_services="|"
    first=1

    emit_json_ports() {
        ports="$1"
        pfirst=1
        printf '['
        for p in $ports; do
            [ "$pfirst" = "1" ] || printf ','
            pfirst=0
            printf '%s' "$p"
        done
        printf ']'
    }

    emit_app_service() {
        id="$1"; label="$2"; svc="$3"; pattern="$4"; detail="$5"; slug="$6"; pkg_hint="$7"; web_hint="$8"
        service_installed_evidence "$id" "$svc" "$slug" "$pkg_hint" || return 0

        pid="$(first_pid_for_app "$id" "$svc" "$pattern" 2>/dev/null || true)"
        if [ -n "$svc" ] && service_running "$svc"; then
            running=true
        elif [ -n "$pid" ]; then
            running=true
        else
            running=false
        fi

        if [ -n "$svc" ]; then
            service_enabled "$svc" && enabled=true || enabled=false
            [ -x "/etc/init.d/$svc" ] && controllable=true || controllable=false
            seen_services="${seen_services}${svc}|"
        else
            enabled=false
            controllable=false
        fi

        rss=""
        uptime=""
        ports=""
        if [ -n "$pid" ]; then
            rss="$(pid_rss_kb "$pid")"
            uptime="$(pid_uptime_seconds "$pid")"
            ports="$(listen_ports_for_pid "$pid" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
        fi

        pkg="$(installed_package_for "$id" "$svc" "$pkg_hint")"
        version="$(package_version "$pkg")"
        [ "$running" = "true" ] && health="healthy" || health="stopped"

        panel_available=false
        panel_path=""
        panel_kind=""
        panel_port=0
        panel_scheme="http"
        panel_host="127.0.0.1"

        if [ -n "$slug" ] && luci_slug_exists "$slug"; then
            panel_available=true
            panel_path="/cgi-bin/luci/admin/services/$slug"
            panel_kind="luci"
            panel_port="$luci_port"
            panel_scheme="$luci_scheme"
            seen="${seen}${slug}|"
        elif [ "$web_hint" = "1" ] && [ -n "$ports" ]; then
            panel="$(standalone_panel_for "$id" "$ports")"
            if [ -n "$panel" ]; then
                set -- $panel
                panel_scheme="$1"
                panel_port="$2"
                panel_path="/"
                panel_kind="standalone"
                panel_available=true
            fi
        fi

        [ $first -eq 1 ] || printf ','
        first=0
        printf '{"id":'; q "$id"
        printf ',"display_name":'; q "$label"
        printf ',"init_service":'; q "$svc"
        printf ',"controllable":%s,"running":%s,"enabled":%s' "$controllable" "$running" "$enabled"
        printf ',"health":'; q "$health"
        printf ',"version":'; q "$version"
        printf ',"package_name":'; q "$pkg"
        printf ',"pid":'
        [ -n "$pid" ] && printf '%s' "$pid" || printf 'null'
        printf ',"uptime_seconds":'
        [ -n "$uptime" ] && printf '%s' "$uptime" || printf 'null'
        printf ',"cpu_percent":null'
        printf ',"memory_kb":'
        [ -n "$rss" ] && printf '%s' "$rss" || printf 'null'
        printf ',"ports":'; emit_json_ports "$ports"
        printf ',"detail":'; q "$detail"
        printf ',"panel_available":%s' "$panel_available"
        printf ',"panel_path":'; q "$panel_path"
        printf ',"panel_port":%s' "$panel_port"
        printf ',"panel_scheme":'; q "$panel_scheme"
        printf ',"panel_kind":'; q "$panel_kind"
        printf ',"panel_host":'; q "$panel_host"
        printf '}'
    }

    emit_panel_only() {
        slug="$1"
        case "$seen" in *"|$slug|"*) return 0 ;; esac
        label="$(luci_title_for_slug "$slug")"
        seen="${seen}${slug}|"

        [ $first -eq 1 ] || printf ','
        first=0
        printf '{"id":'; q "panel-$slug"
        printf ',"display_name":'; q "$label"
        printf ',"init_service":"","controllable":false,"running":true,"enabled":false'
        printf ',"health":"panel","version":"","package_name":"","pid":null,"uptime_seconds":null'
        printf ',"cpu_percent":null,"memory_kb":null,"ports":[]'
        printf ',"detail":"LuCI 功能面板"'
        printf ',"panel_available":true'
        printf ',"panel_path":'; q "/cgi-bin/luci/admin/services/$slug"
        printf ',"panel_port":%s' "$luci_port"
        printf ',"panel_scheme":'; q "$luci_scheme"
        printf ',"panel_kind":"luci","panel_host":"127.0.0.1"}'
    }

    emit_generic_web_service() {
        svc="$1"
        system_service_name "$svc" && return 0
        case "$seen_services" in *"|$svc|"*) return 0 ;; esac

        pid="$(service_pid "$svc" 2>/dev/null || true)"
        [ -n "$pid" ] || pid="$(first_pid_for_app "$svc" "$svc" "$svc" 2>/dev/null || true)"
        [ -n "$pid" ] || return 0

        ports="$(listen_ports_for_pid "$pid" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
        panel_available=false
        panel_kind=""
        panel_path=""
        panel_scheme="http"
        panel_port=0

        if luci_slug_exists "$svc"; then
            panel_available=true
            panel_kind="luci"
            panel_path="/cgi-bin/luci/admin/services/$svc"
            panel_scheme="$luci_scheme"
            panel_port="$luci_port"
            label="$(luci_title_for_slug "$svc")"
            seen="${seen}${svc}|"
        else
            label="$svc"
            for p in $ports; do
                scheme="$(http_scheme_for_port "$p")"
                [ -n "$scheme" ] || continue
                panel_available=true
                panel_kind="standalone"
                panel_path="/"
                panel_scheme="$scheme"
                panel_port="$p"
                break
            done
        fi

        [ "$panel_available" = "true" ] || return 0

        service_enabled "$svc" && enabled=true || enabled=false
        service_running "$svc" && running=true || running=false
        rss="$(pid_rss_kb "$pid")"
        uptime="$(pid_uptime_seconds "$pid")"
        pkg="$(installed_package_for "$svc" "$svc" "")"
        version="$(package_version "$pkg")"

        [ $first -eq 1 ] || printf ','
        first=0
        printf '{"id":'; q "auto-$svc"
        printf ',"display_name":'; q "$label"
        printf ',"init_service":'; q "$svc"
        printf ',"controllable":true,"running":%s,"enabled":%s' "$running" "$enabled"
        printf ',"health":"healthy","version":'; q "$version"
        printf ',"package_name":'; q "$pkg"
        printf ',"pid":%s,"uptime_seconds":' "$pid"
        [ -n "$uptime" ] && printf '%s' "$uptime" || printf 'null'
        printf ',"cpu_percent":null,"memory_kb":'
        [ -n "$rss" ] && printf '%s' "$rss" || printf 'null'
        printf ',"ports":'; emit_json_ports "$ports"
        printf ',"detail":"自动发现应用服务"'
        printf ',"panel_available":true'
        printf ',"panel_path":'; q "$panel_path"
        printf ',"panel_port":%s' "$panel_port"
        printf ',"panel_scheme":'; q "$panel_scheme"
        printf ',"panel_kind":'; q "$panel_kind"
        printf ',"panel_host":"127.0.0.1"}'
        seen_services="${seen_services}${svc}|"
    }

    printf '['

    emit_app_service "nikki" "Nikki" "nikki" "mihomo" "代理服务" "nikki" "luci-app-nikki" "0"
    emit_app_service "homeproxy" "HomeProxy" "homeproxy" "sing-box" "代理服务" "homeproxy" "luci-app-homeproxy" "0"
    emit_app_service "mosdns" "MosDNS" "mosdns" "mosdns" "DNS 服务" "mosdns" "luci-app-mosdns" "0"
    emit_app_service "ddns-go" "DDNS-Go" "ddns-go" "ddns-go" "动态域名" "ddns-go" "ddns-go" "1"
    emit_app_service "nezha-agent" "Nezha Agent" "nezha-agent" "nezha-agent" "监控探针" "nezha-agent" "nezha-agent" "0"
    emit_app_service "miniupnpd" "UPnP IGD 和 PCP" "miniupnpd" "miniupnpd" "UPnP / PCP 服务" "upnp" "miniupnpd" "0"
    emit_app_service "openclash" "OpenClash" "openclash" "clash" "代理服务" "openclash" "luci-app-openclash" "0"
    emit_app_service "passwall" "PassWall" "passwall" "sing-box" "代理服务" "passwall" "luci-app-passwall" "0"
    emit_app_service "passwall2" "PassWall2" "passwall2" "sing-box" "代理服务" "passwall2" "luci-app-passwall2" "0"
    emit_app_service "adguardhome" "AdGuard Home" "AdGuardHome" "AdGuardHome" "DNS / 广告过滤" "adguardhome" "adguardhome" "1"
    emit_app_service "smartdns" "SmartDNS" "smartdns" "smartdns" "DNS 服务" "smartdns" "smartdns" "0"
    emit_app_service "tailscale" "Tailscale" "tailscale" "tailscaled" "组网服务" "" "tailscale" "0"
    emit_app_service "zerotier" "ZeroTier" "zerotier" "zerotier-one" "组网服务" "" "zerotier" "0"
    emit_app_service "docker" "Docker" "dockerd" "dockerd" "容器服务" "" "dockerd" "0"
    emit_app_service "samba4" "Samba" "samba4" "smbd" "文件共享" "" "samba4-server" "0"
    emit_app_service "samba" "Samba" "samba" "smbd" "文件共享" "" "samba36-server" "0"

    # Discover third-party services first so an init.d service and its
    # matching LuCI page become one controllable application card instead of
    # separate "service" and "panel" duplicates.
    for init in /etc/init.d/*; do
        [ -x "$init" ] || continue
        svc="${init##*/}"
        emit_generic_web_service "$svc"
    done

    # Any remaining LuCI Services entries are panel-only functions such as
    # Wake on LAN or vendor-specific HTTPS configuration pages.
    for file in /usr/share/luci/menu.d/*.json; do
        [ -f "$file" ] || continue
        grep -o '"admin/services/[^"]*"' "$file" 2>/dev/null | tr -d '"' | while IFS= read -r route; do
            slug="${route#admin/services/}"
            slug="${slug%%/*}"
            [ -n "$slug" ] || continue
            printf '%s\n' "$slug"
        done
    done | sort -u | while IFS= read -r slug; do
        emit_panel_only "$slug"
    done

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
    [ "$lines" -gt 1000 ] && lines=1000
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

ensure_block_rule() {
    mac="$1"
    sec="$(rule_name "$mac")"
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
}

remove_block_rule_if_unused() {
    mac="$1"
    effective_blocked "$mac" && return 0
    sec="$(rule_name "$mac")"
    uci -q delete "firewall.$sec" || true
    uci commit firewall
    firewall_reload || true
}

add_mac_to_file() {
    file="$1"; mac="$2"
    mkdir -p "$BASE"; touch "$file"
    grep -qiFx "$mac" "$file" || echo "$mac" >> "$file"
    sort -u "$file" -o "$file" 2>/dev/null || true
}

remove_mac_from_file() {
    file="$1"; mac="$2"
    [ -f "$file" ] || return 0
    grep -viFx "$mac" "$file" > "$file.tmp" || true
    mv "$file.tmp" "$file"
}

cmd_block() {
    mac="$(echo "$1" | tr a-f A-F)"; valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }
    add_mac_to_file "$BLOCKED" "$mac"
    ensure_block_rule "$mac"
    echo '{"ok":true}'
}

cmd_unblock() {
    mac="$(echo "$1" | tr a-f A-F)"; valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }
    remove_mac_from_file "$BLOCKED" "$mac"
    remove_block_rule_if_unused "$mac"
    echo '{"ok":true}'
}

cmd_schedule_block() {
    mac="$(echo "$1" | tr a-f A-F)"; valid_mac "$mac" || exit 2
    add_mac_to_file "$SCHEDULE_BLOCKED" "$mac"
    ensure_block_rule "$mac"
    echo '{"ok":true}'
}

cmd_schedule_unblock() {
    mac="$(echo "$1" | tr a-f A-F)"; valid_mac "$mac" || exit 2
    remove_mac_from_file "$SCHEDULE_BLOCKED" "$mac"
    remove_block_rule_if_unused "$mac"
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
    device-traffic-capability) cmd_device_traffic_capability ;;
    device-traffic) cmd_device_traffic ;;
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
    schedule-block) cmd_schedule_block "${2:-}" ;;
    schedule-unblock) cmd_schedule_unblock "${2:-}" ;;
    service) cmd_service "${2:-}" "${3:-}" ;;
    network-restart) echo '{"ok":true}'; /etc/init.d/network restart >/dev/null 2>&1 & ;;
    reboot) echo '{"ok":true}'; sync; (sleep 1; reboot) >/dev/null 2>&1 & ;;
    *) echo '{"error":"unknown command"}'; exit 2 ;;
esac
