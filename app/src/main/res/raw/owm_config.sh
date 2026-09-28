#!/bin/sh
# OpenWrt Manager Config Helper V0.1.3
set -u

VERSION="0.1.3"
BASE="/etc/openwrt-manager"
SAFE="$BASE/safe-apply"
SELF="/usr/bin/owm-config"

q() {
    printf '"'
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r/\\r/g'
    printf '"'
}

uci_get() { uci -q get "$1" 2>/dev/null || true; }
valid_name() { echo "$1" | grep -Eq '^[A-Za-z0-9_.@+-]+$'; }
valid_num() { echo "$1" | grep -Eq '^[0-9]+$'; }
valid_ipv4() {
    echo "$1" | awk -F. 'NF==4 {for(i=1;i<=4;i++) if($i !~ /^[0-9]+$/ || $i<0 || $i>255) exit 1; exit 0} {exit 1}'
}
decode_b64() {
    [ -n "$1" ] || { printf ''; return; }
    printf '%s' "$1" | base64 -d 2>/dev/null || true
}

cmd_install() {
    mkdir -p "$BASE" "$SAFE"
    if [ "$0" != "$SELF" ]; then cp "$0" "$SELF"; fi
    chmod 700 "$SELF"
    printf '{"ok":true,"version":"%s"}\n' "$VERSION"
}

cmd_config() {
    lan_ip="$(uci_get network.lan.ipaddr)"
    lan_mask="$(uci_get network.lan.netmask)"
    dhcp_start="$(uci_get dhcp.lan.start)"
    dhcp_limit="$(uci_get dhcp.lan.limit)"
    dhcp_lease="$(uci_get dhcp.lan.leasetime)"
    peer="$(uci_get network.wan.peerdns)"
    [ "$peer" = "0" ] && dns_peer=false || dns_peer=true

    wan_proto="$(uci_get network.wan.proto)"
    wan_user="$(uci_get network.wan.username)"
    wan_pass="$(uci_get network.wan.password)"
    [ -n "$wan_pass" ] && wan_pass_set=true || wan_pass_set=false
    wan_mtu="$(uci_get network.wan.mtu)"
    wan_ip="$(uci_get network.wan.ipaddr)"
    wan_mask="$(uci_get network.wan.netmask)"
    wan_gw="$(uci_get network.wan.gateway)"
    wan6_disabled="$(uci_get network.wan6.disabled)"
    [ "$wan6_disabled" = "1" ] && wan6_enabled=false || wan6_enabled=true

    printf '{"lan_ip":'; q "$lan_ip"
    printf ',"lan_netmask":'; q "$lan_mask"
    printf ',"dhcp_start":'; q "$dhcp_start"
    printf ',"dhcp_limit":'; q "$dhcp_limit"
    printf ',"dhcp_leasetime":'; q "$dhcp_lease"
    printf ',"dns_peer":%s,"dns_servers":[' "$dns_peer"

    first=1
    for d in $(uci -q get network.wan.dns 2>/dev/null); do
        [ "$first" = "1" ] || printf ','
        first=0
        q "$d"
    done

    printf '],"wan_proto":'; q "$wan_proto"
    printf ',"wan_username":'; q "$wan_user"
    printf ',"wan_password_set":%s' "$wan_pass_set"
    printf ',"wan_mtu":'; q "$wan_mtu"
    printf ',"wan_ip":'; q "$wan_ip"
    printf ',"wan_netmask":'; q "$wan_mask"
    printf ',"wan_gateway":'; q "$wan_gw"
    printf ',"wan6_enabled":%s}\n' "$wan6_enabled"
}

active_meta() { echo "$SAFE/active"; }

cmd_safe_begin() {
    kind="$1"
    timeout="$2"
    valid_num "$timeout" || timeout=90
    [ "$timeout" -ge 45 ] || timeout=45
    [ "$timeout" -le 180 ] || timeout=180
    mkdir -p "$SAFE"

    meta="$(active_meta)"
    if [ -f "$meta" ]; then
        old="$(sed -n '1p' "$meta" 2>/dev/null)"
        [ -n "$old" ] && "$SELF" safe-rollback "$old" >/dev/null 2>&1 || true
    fi

    tx="$(date +%s)-$$"
    backup="$SAFE/$tx"
    mkdir -p "$backup"

    for name in network wireless dhcp firewall; do
        [ -f "/etc/config/$name" ] && cp -p "/etc/config/$name" "$backup/$name"
    done

    deadline=$(( $(date +%s) + timeout ))
    printf '%s\n%s\n%s\n' "$tx" "$kind" "$deadline" > "$meta"

    (
        sleep "$timeout"
        "$SELF" safe-rollback "$tx" >/dev/null 2>&1
    ) >/dev/null 2>&1 &
    echo $! > "$backup/watchdog.pid"

    printf '{"active":true,"transaction_id":'; q "$tx"
    printf ',"kind":'; q "$kind"
    printf ',"deadline_epoch":%s,"seconds_remaining":%s}\n' "$deadline" "$timeout"
}

cmd_safe_status() {
    meta="$(active_meta)"
    if [ ! -f "$meta" ]; then
        echo '{"active":false,"transaction_id":"","kind":"","deadline_epoch":0,"seconds_remaining":0}'
        return
    fi

    tx="$(sed -n '1p' "$meta")"
    kind="$(sed -n '2p' "$meta")"
    deadline="$(sed -n '3p' "$meta")"
    now="$(date +%s)"
    remain=$((deadline - now))
    [ "$remain" -gt 0 ] || remain=0

    printf '{"active":true,"transaction_id":'; q "$tx"
    printf ',"kind":'; q "$kind"
    printf ',"deadline_epoch":%s,"seconds_remaining":%s}\n' "$deadline" "$remain"
}

cmd_safe_confirm() {
    tx="$1"
    meta="$(active_meta)"
    [ -f "$meta" ] || { echo '{"ok":false,"error":"no active transaction"}'; exit 3; }
    active="$(sed -n '1p' "$meta")"
    [ "$tx" = "$active" ] || { echo '{"ok":false,"error":"transaction mismatch"}'; exit 4; }

    backup="$SAFE/$tx"
    if [ -f "$backup/watchdog.pid" ]; then
        kill "$(cat "$backup/watchdog.pid")" >/dev/null 2>&1 || true
    fi
    rm -f "$meta"
    rm -rf "$backup"
    echo '{"ok":true}'
}

cmd_safe_rollback() {
    tx="$1"
    [ -n "$tx" ] || exit 2
    meta="$(active_meta)"
    active=""
    [ -f "$meta" ] && active="$(sed -n '1p' "$meta" 2>/dev/null)"
    [ -z "$active" ] || [ "$active" = "$tx" ] || exit 4

    backup="$SAFE/$tx"
    [ -d "$backup" ] || { rm -f "$meta"; echo '{"ok":false,"error":"backup missing"}'; exit 5; }

    for name in network wireless dhcp firewall; do
        [ -f "$backup/$name" ] && cp -p "$backup/$name" "/etc/config/$name"
    done
    sync
    rm -f "$meta"

    if [ -f "$backup/watchdog.pid" ]; then
        pid="$(cat "$backup/watchdog.pid" 2>/dev/null)"
        [ "$pid" = "$$" ] || kill "$pid" >/dev/null 2>&1 || true
    fi
    rm -rf "$backup"

    echo '{"ok":true,"rolled_back":true}'
    (
        sleep 1
        /etc/init.d/network restart >/dev/null 2>&1 || true
        wifi reload >/dev/null 2>&1 || true
        /etc/init.d/dnsmasq restart >/dev/null 2>&1 || true
    ) >/dev/null 2>&1 &
}

cmd_set_wifi() {
    iface="$1"
    dev="$2"
    enabled="$3"
    ssid="$(decode_b64 "$4")"
    enc="$5"
    pass="$(decode_b64 "$6")"
    channel="$7"
    htmode="$8"
    country="$9"

    valid_name "$iface" && valid_name "$dev" || exit 2
    [ -n "$ssid" ] || { echo '{"ok":false,"error":"ssid required"}'; exit 3; }

    uci set "wireless.$iface.ssid=$ssid"
    [ -n "$enc" ] && uci set "wireless.$iface.encryption=$enc"

    if [ "$enabled" = "1" ]; then
        uci -q delete "wireless.$iface.disabled"
    else
        uci set "wireless.$iface.disabled=1"
    fi

    [ -n "$pass" ] && uci set "wireless.$iface.key=$pass"
    [ -n "$channel" ] && uci set "wireless.$dev.channel=$channel"
    [ -n "$htmode" ] && uci set "wireless.$dev.htmode=$htmode"
    [ -n "$country" ] && uci set "wireless.$dev.country=$country"

    uci commit wireless
    echo '{"ok":true}'
}

cmd_set_lan() {
    ip="$1"
    mask="$2"
    valid_ipv4 "$ip" && valid_ipv4 "$mask" || { echo '{"ok":false,"error":"invalid LAN address"}'; exit 2; }
    uci set "network.lan.ipaddr=$ip"
    uci set "network.lan.netmask=$mask"
    uci commit network
    echo '{"ok":true}'
}

cmd_set_dhcp() {
    start="$1"
    limit="$2"
    lease="$3"
    valid_num "$start" && valid_num "$limit" || { echo '{"ok":false,"error":"invalid DHCP range"}'; exit 2; }
    echo "$lease" | grep -Eq '^[0-9]+[mhdw]$' || { echo '{"ok":false,"error":"invalid lease time"}'; exit 3; }
    uci set "dhcp.lan.start=$start"
    uci set "dhcp.lan.limit=$limit"
    uci set "dhcp.lan.leasetime=$lease"
    uci commit dhcp
    echo '{"ok":true}'
}

cmd_set_dns() {
    peer="$1"
    packed="$(decode_b64 "$2")"

    if [ "$peer" = "0" ]; then
        uci set network.wan.peerdns=0
    else
        uci -q delete network.wan.peerdns
    fi

    uci -q delete network.wan.dns
    for d in $packed; do
        echo "$d" | grep -Eq '^[0-9A-Fa-f:.]+$' || continue
        uci add_list "network.wan.dns=$d"
    done

    uci commit network
    echo '{"ok":true}'
}

cmd_set_wan() {
    proto="$1"
    user="$(decode_b64 "$2")"
    pass="$(decode_b64 "$3")"
    ip="$4"
    mask="$5"
    gw="$6"
    mtu="$7"

    case "$proto" in
        dhcp|pppoe|static) ;;
        *) echo '{"ok":false,"error":"unsupported WAN protocol"}'; exit 2 ;;
    esac

    uci set "network.wan.proto=$proto"

    if [ "$proto" = "pppoe" ]; then
        [ -n "$user" ] && uci set "network.wan.username=$user"
        [ -n "$pass" ] && uci set "network.wan.password=$pass"
        uci -q delete network.wan.ipaddr
        uci -q delete network.wan.netmask
        uci -q delete network.wan.gateway
    elif [ "$proto" = "static" ]; then
        valid_ipv4 "$ip" && valid_ipv4 "$mask" && valid_ipv4 "$gw" || {
            echo '{"ok":false,"error":"invalid static WAN"}'
            exit 3
        }
        uci set "network.wan.ipaddr=$ip"
        uci set "network.wan.netmask=$mask"
        uci set "network.wan.gateway=$gw"
        uci -q delete network.wan.username
        uci -q delete network.wan.password
    else
        uci -q delete network.wan.username
        uci -q delete network.wan.password
        uci -q delete network.wan.ipaddr
        uci -q delete network.wan.netmask
        uci -q delete network.wan.gateway
    fi

    if [ -n "$mtu" ]; then
        valid_num "$mtu" || { echo '{"ok":false,"error":"invalid mtu"}'; exit 4; }
        uci set "network.wan.mtu=$mtu"
    else
        uci -q delete network.wan.mtu
    fi

    uci commit network
    echo '{"ok":true}'
}

cmd_set_wan6() {
    enabled="$1"
    if [ "$enabled" = "1" ]; then
        uci -q delete network.wan6.disabled
        [ -n "$(uci_get network.wan6.proto)" ] || uci set network.wan6.proto=dhcpv6
    else
        uci set network.wan6.disabled=1
    fi
    uci commit network
    echo '{"ok":true}'
}

cmd_apply() {
    kind="$1"
    echo '{"ok":true,"applying":true}'

    case "$kind" in
        wifi)
            (sleep 1; wifi reload >/dev/null 2>&1 || /etc/init.d/network reload >/dev/null 2>&1) >/dev/null 2>&1 &
            ;;
        dhcp)
            (sleep 1; /etc/init.d/dnsmasq restart >/dev/null 2>&1) >/dev/null 2>&1 &
            ;;
        *)
            (
                sleep 1
                /etc/init.d/network restart >/dev/null 2>&1 || true
                /etc/init.d/dnsmasq restart >/dev/null 2>&1 || true
            ) >/dev/null 2>&1 &
            ;;
    esac
}

case "$1" in
    install) cmd_install ;;
    version) printf '{"version":"%s"}\n' "$VERSION" ;;
    config) cmd_config ;;
    safe-begin) cmd_safe_begin "$2" "$3" ;;
    safe-status) cmd_safe_status ;;
    safe-confirm) cmd_safe_confirm "$2" ;;
    safe-rollback) cmd_safe_rollback "$2" ;;
    set-wifi) cmd_set_wifi "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "$10" ;;
    set-lan) cmd_set_lan "$2" "$3" ;;
    set-dhcp) cmd_set_dhcp "$2" "$3" "$4" ;;
    set-dns) cmd_set_dns "$2" "$3" ;;
    set-wan) cmd_set_wan "$2" "$3" "$4" "$5" "$6" "$7" "$8" ;;
    set-wan6) cmd_set_wan6 "$2" ;;
    apply) cmd_apply "$2" ;;
    *) echo '{"error":"unknown command"}'; exit 2 ;;
esac
