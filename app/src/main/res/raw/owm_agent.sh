#!/bin/sh
# OpenWrt Manager Agent V0.1.1
# Command agent used over SSH. It opens no listening socket.
set -u
VERSION="0.1.1"
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
    mem_total="$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null)"
    mem_avail="$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)"
    [ -n "${mem_avail:-}" ] || mem_avail="$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)"
    dfline="$(df -kP /overlay 2>/dev/null | tail -n1)"
    [ -n "$dfline" ] || dfline="$(df -kP / 2>/dev/null | tail -n1)"
    root_total="$(echo "$dfline" | awk '{print $2}')"
    root_free="$(echo "$dfline" | awk '{print $4}')"
    temp=""
    for f in /sys/class/thermal/thermal_zone*/temp; do
        [ -r "$f" ] || continue
        raw="$(cat "$f" 2>/dev/null)"
        case "$raw" in ''|*[!0-9]*) ;; *) temp="$(awk -v t="$raw" 'BEGIN{printf "%.1f", t>1000?t/1000:t}')"; break;; esac
    done
    printf '{"hostname":'; q "$hostname"; printf ',"model":'; q "$model"; printf ',"firmware":'; q "$firmware";
    printf ',"kernel":'; q "$kernel"; printf ',"arch":'; q "$arch";
    printf ',"uptime":%s,"load1":%s,"mem_total_kb":%s,"mem_available_kb":%s,"root_total_kb":%s,"root_free_kb":%s,"temperature_c":' \
        "${uptime:-0}" "${load1:-0}" "${mem_total:-0}" "${mem_avail:-0}" "${root_total:-0}" "${root_free:-0}"
    [ -n "$temp" ] && printf '%s' "$temp" || printf 'null'
    printf '}\n'
}

wifi_has_mac() {
    mac="$1"
    for obj in $(ubus list 'hostapd.*' 2>/dev/null); do
        ubus call "$obj" get_clients 2>/dev/null | grep -qi "$mac" && return 0
    done
    return 1
}

cmd_devices() {
    # A device may have several IPv4/IPv6 neighbour entries. The APP treats MAC
    # as the device identity, so only emit one IPv4 record per MAC. This also
    # avoids duplicate Compose list keys and keeps stale IPv6 neighbours out.
    first=1
    seen=" "
    printf '['
    ip -4 neigh show 2>/dev/null | while IFS= read -r line; do
        set -- $line
        ip="${1:-}"; ifname="${3:-}"; mac="${5:-}"; state="${6:-}"
        valid_mac "$mac" || continue
        [ "$state" = "FAILED" ] && continue
        mac_upper="$(echo "$mac" | tr a-f A-F)"
        case "$seen" in *" $mac_upper "*) continue ;; esac
        seen="$seen$mac_upper "
        hostname="$(awk -v m="$mac" 'tolower($2)==tolower(m){print $4; exit}' /tmp/dhcp.leases 2>/dev/null)"
        [ -n "$hostname" ] || hostname="$(awk -v i="$ip" '$3==i{print $4; exit}' /tmp/dhcp.leases 2>/dev/null)"
        type="ethernet"; wifi_has_mac "$mac" && type="wifi"
        blocked=false; blocked_has "$mac_upper" && blocked=true
        [ $first -eq 1 ] || printf ','; first=0
        printf '{"ip":'; q "$ip"; printf ',"mac":'; q "$mac_upper"; printf ',"hostname":'; q "$hostname";
        printf ',"interface":'; q "$ifname"; printf ',"state":'; q "$state"; printf ',"type":'; q "$type"; printf ',"blocked":%s}' "$blocked"
    done
    printf ']\n'
}

iface_value() {
    json="$1"; expr="$2"
    printf '%s' "$json" | jsonfilter -e "$expr" 2>/dev/null | head -n1
}
cmd_network() {
    wan="$(ubus call network.interface.wan status 2>/dev/null || echo '{}')"
    lan="$(ubus call network.interface.lan status 2>/dev/null || echo '{}')"
    wan_proto="$(iface_value "$wan" '@.proto')"; wan_dev="$(iface_value "$wan" '@.device')"; wan_uptime="$(iface_value "$wan" '@.uptime')"
    wan4="$(iface_value "$wan" '@["ipv4-address"][0].address')"; wan6="$(iface_value "$wan" '@["ipv6-address"][0].address')"
    lan_dev="$(iface_value "$lan" '@.device')"; lan4="$(iface_value "$lan" '@["ipv4-address"][0].address')"; lan6="$(iface_value "$lan" '@["ipv6-address"][0].address')"
    printf '{"wan_proto":'; q "$wan_proto"; printf ',"wan_device":'; q "$wan_dev"; printf ',"wan_ipv4":'; q "$wan4"; printf ',"wan_ipv6":'; q "$wan6";
    printf ',"wan_uptime":%s,"lan_device":' "${wan_uptime:-0}"; q "$lan_dev"; printf ',"lan_ipv4":'; q "$lan4"; printf ',"lan_ipv6":'; q "$lan6"; printf '}\n'
}

uci_get() { uci -q get "$1" 2>/dev/null || true; }
cmd_wifi() {
    first=1; printf '['
    for sec in $(uci -q show wireless 2>/dev/null | sed -n "s/^wireless\.\([^.=]*\)=wifi-device$/\1/p"); do
        [ $first -eq 1 ] || printf ','; first=0
        disabled="$(uci_get wireless.$sec.disabled)"; [ "$disabled" = "1" ] && dis=true || dis=false
        printf '{"section":'; q "$sec"; printf ',"kind":"radio","channel":'; q "$(uci_get wireless.$sec.channel)";
        printf ',"band":'; q "$(uci_get wireless.$sec.band)"; printf ',"htmode":'; q "$(uci_get wireless.$sec.htmode)"; printf ',"disabled":%s}' "$dis"
    done
    for sec in $(uci -q show wireless 2>/dev/null | sed -n "s/^wireless\.\([^.=]*\)=wifi-iface$/\1/p"); do
        [ $first -eq 1 ] || printf ','; first=0
        disabled="$(uci_get wireless.$sec.disabled)"; [ "$disabled" = "1" ] && dis=true || dis=false
        printf '{"section":'; q "$sec"; printf ',"kind":"iface","device":'; q "$(uci_get wireless.$sec.device)";
        printf ',"ssid":'; q "$(uci_get wireless.$sec.ssid)"; printf ',"encryption":'; q "$(uci_get wireless.$sec.encryption)"; printf ',"disabled":%s}' "$dis"
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
        [ $first -eq 1 ] || printf ','; first=0
        printf '{"name":'; q "$name"; printf ',"enabled":%s,"running":%s}' "$enabled" "$running"
    done
    printf ']\n'
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
    "/etc/init.d/$name" "$action" >/dev/null 2>&1
    printf '{"ok":true}\n'
}

case "${1:-}" in
    install) cmd_install ;;
    version) printf '{"version":"%s","protocol":1}\n' "$VERSION" ;;
    status) cmd_status ;;
    devices) cmd_devices ;;
    network) cmd_network ;;
    wifi) cmd_wifi ;;
    services) cmd_services ;;
    logs) lines="${2:-200}"; case "$lines" in *[!0-9]*) lines=200;; esac; logread -l "$lines" 2>/dev/null || logread 2>/dev/null | tail -n "$lines" ;;
    block) cmd_block "${2:-}" ;;
    unblock) cmd_unblock "${2:-}" ;;
    service) cmd_service "${2:-}" "${3:-}" ;;
    network-restart) echo '{"ok":true}'; /etc/init.d/network restart >/dev/null 2>&1 & ;;
    reboot) echo '{"ok":true}'; sync; (sleep 1; reboot) >/dev/null 2>&1 & ;;
    *) echo '{"error":"unknown command"}'; exit 2 ;;
esac
