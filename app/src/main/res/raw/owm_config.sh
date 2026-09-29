#!/bin/sh
# OpenWrt Manager Config Helper V0.2.0
set -u

VERSION="0.2.0"
BASE="/etc/openwrt-manager"
SAFE="$BASE/safe-apply"
ALIASES="$BASE/device_aliases.tsv"
SCHEDULES="$BASE/device_schedules.tsv"
QOS_POLICIES="$BASE/device_qos.tsv"
QOS_INIT="/etc/init.d/owm-qos"
CRON="/etc/crontabs/root"
SELF="/usr/bin/owm-config"

q() {
    printf '"'
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r/\\r/g'
    printf '"'
}

uci_get() { uci -q get "$1" 2>/dev/null || true; }
valid_name() { echo "$1" | grep -Eq '^[A-Za-z0-9_.@+-]+$'; }
valid_mac() { echo "$1" | grep -Eq '^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$'; }
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
    touch "$ALIASES" "$SCHEDULES" "$QOS_POLICIES" /etc/sysupgrade.conf
    grep -qxF '/etc/openwrt-manager/' /etc/sysupgrade.conf 2>/dev/null || echo '/etc/openwrt-manager/' >> /etc/sysupgrade.conf
    if [ "$0" != "$SELF" ]; then cp "$0" "$SELF"; fi
    chmod 700 "$SELF"
    cat > "$QOS_INIT" <<'EOF'
#!/bin/sh /etc/rc.common
START=22
STOP=88

start() {
    /usr/bin/owm-config qos-reload >/dev/null 2>&1 || true
}

reload() {
    start
}

stop() {
    /usr/bin/owm-config qos-stop >/dev/null 2>&1 || true
}
EOF
    chmod 755 "$QOS_INIT"
    "$QOS_INIT" enable >/dev/null 2>&1 || true
    regen_cron >/dev/null 2>&1 || true
    qos_reload >/dev/null 2>&1 || true
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


device_key() {
    printf 'owm_%s' "$(printf '%s' "$1" | tr -d ':' | tr A-F a-f)"
}

alias_encoded() {
    [ -f "$ALIASES" ] || return 0
    awk -F '\t' -v m="$1" 'toupper($1)==toupper(m){print $2; exit}' "$ALIASES" 2>/dev/null
}

alias_value() {
    enc="$(alias_encoded "$1")"
    [ -n "$enc" ] || return 0
    printf '%s' "$enc" | base64 -d 2>/dev/null || true
}

set_alias_value() {
    mac="$1"
    alias="$(decode_b64 "$2")"
    alias="$(printf '%s' "$alias" | tr '\r\n\t' '   ')"
    encoded=""
    [ -n "$alias" ] && encoded="$(printf '%s' "$alias" | base64 | tr -d '\r\n')"

    mkdir -p "$BASE"
    touch "$ALIASES"
    awk -F '\t' -v m="$mac" 'toupper($1)!=toupper(m){print}' "$ALIASES" > "$ALIASES.tmp" || true
    if [ -n "$encoded" ]; then
        printf '%s\t%s\n' "$mac" "$encoded" >> "$ALIASES.tmp"
    fi
    mv "$ALIASES.tmp" "$ALIASES"
}

find_dhcp_host_by_mac() {
    target="$(printf '%s' "$1" | tr A-F a-f)"
    for sec in $(uci -q show dhcp 2>/dev/null | sed -n 's/^dhcp\.\([^.=]*\)=host$/\1/p'); do
        macs="$(uci -q get "dhcp.$sec.mac" 2>/dev/null)"
        for m in $macs; do
            [ "$(printf '%s' "$m" | tr A-F a-f)" = "$target" ] && { printf '%s' "$sec"; return 0; }
        done
    done
    return 1
}

find_dhcp_host_by_ip() {
    target="$1"
    for sec in $(uci -q show dhcp 2>/dev/null | sed -n 's/^dhcp\.\([^.=]*\)=host$/\1/p'); do
        ip="$(uci -q get "dhcp.$sec.ip" 2>/dev/null)"
        [ "$ip" = "$target" ] && { printf '%s' "$sec"; return 0; }
    done
    return 1
}

cmd_device_alias() {
    mac="$(printf '%s' "$1" | tr a-f A-F)"
    valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }
    set_alias_value "$mac" "$2"
    echo '{"ok":true}'
}

cmd_device_static_ip() {
    mac="$(printf '%s' "$1" | tr a-f A-F)"
    ip="$2"
    valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }

    sec="$(find_dhcp_host_by_mac "$mac" 2>/dev/null || true)"

    if [ -z "$ip" ]; then
        if [ -n "$sec" ]; then
            case "$sec" in
                owm_*) uci -q delete "dhcp.$sec" || true ;;
                *) uci -q delete "dhcp.$sec.ip" || true ;;
            esac
            uci commit dhcp
            /etc/init.d/dnsmasq restart >/dev/null 2>&1 || true
        fi
        echo '{"ok":true}'
        return
    fi

    valid_ipv4 "$ip" || { echo '{"ok":false,"error":"invalid static ip"}'; exit 3; }

    lan_ip="$(uci_get network.lan.ipaddr)"
    [ "$ip" = "$lan_ip" ] && { echo '{"ok":false,"error":"static ip conflicts with router"}'; exit 4; }

    lease_mac="$(awk -v i="$ip" '$3==i{print $2; exit}' /tmp/dhcp.leases 2>/dev/null)"
    if [ -n "$lease_mac" ] && [ "$(printf '%s' "$lease_mac" | tr a-f A-F)" != "$mac" ]; then
        echo '{"ok":false,"error":"static ip is currently leased to another device"}'
        exit 5
    fi

    other="$(find_dhcp_host_by_ip "$ip" 2>/dev/null || true)"
    if [ -n "$other" ]; then
        other_macs="$(uci -q get "dhcp.$other.mac" 2>/dev/null)"
        same=false
        for m in $other_macs; do
            [ "$(printf '%s' "$m" | tr a-f A-F)" = "$mac" ] && same=true
        done
        [ "$same" = "true" ] || { echo '{"ok":false,"error":"static ip already assigned"}'; exit 6; }
    fi

    if [ -z "$sec" ]; then
        sec="$(device_key "$mac")"
        uci -q delete "dhcp.$sec" || true
        uci set "dhcp.$sec=host"
    fi
    uci set "dhcp.$sec.mac=$mac"
    uci set "dhcp.$sec.ip=$ip"
    uci commit dhcp
    /etc/init.d/dnsmasq restart >/dev/null 2>&1 || true
    echo '{"ok":true}'
}

qos_policy_row() {
    [ -f "$QOS_POLICIES" ] || return 0
    awk -F '\t' -v m="$1" 'toupper($1)==toupper(m){print; exit}' "$QOS_POLICIES" 2>/dev/null
}

qos_lan_mode() {
    lan_dev="$(uci_get network.lan.device)"
    [ -n "$lan_dev" ] || lan_dev="$(uci_get network.lan.ifname | awk '{print $1}')"
    if [ -n "$lan_dev" ] && [ -d "/sys/class/net/$lan_dev/bridge" ]; then
        printf '%s' "bridge"
    else
        printf '%s' "inet"
    fi
}

qos_generate_script() {
    outfile="$1"
    table_name="$2"
    mode="$(qos_lan_mode)"

    if [ "$mode" = "bridge" ]; then
        family="bridge"
        upload_hook="prerouting"
        download_hook="postrouting"
    else
        family="inet"
        upload_hook="postrouting"
        download_hook="prerouting"
    fi

    {
        printf 'table %s %s {\n' "$family" "$table_name"
        printf '  chain upload {\n'
        printf '    type filter hook %s priority 0; policy accept;\n' "$upload_hook"
        if [ -f "$QOS_POLICIES" ]; then
            while IFS="$(printf '\t')" read -r mac down up; do
                valid_mac "$mac" || continue
                valid_num "$up" || continue
                [ "$up" -gt 0 ] || continue
                urate=$(((up + 7) / 8))
                [ "$urate" -gt 0 ] || urate=1
                printf '    ether saddr %s limit rate over %s kbytes/second drop\n' "$mac" "$urate"
            done < "$QOS_POLICIES"
        fi
        printf '  }\n'
        printf '  chain download {\n'
        printf '    type filter hook %s priority 0; policy accept;\n' "$download_hook"
        if [ -f "$QOS_POLICIES" ]; then
            while IFS="$(printf '\t')" read -r mac down up; do
                valid_mac "$mac" || continue
                valid_num "$down" || continue
                [ "$down" -gt 0 ] || continue
                drate=$(((down + 7) / 8))
                [ "$drate" -gt 0 ] || drate=1
                printf '    ether daddr %s limit rate over %s kbytes/second drop\n' "$mac" "$drate"
            done < "$QOS_POLICIES"
        fi
        printf '  }\n'
        printf '}\n'
    } > "$outfile"
}

qos_delete_live_table() {
    nft delete table bridge owm_qos_mac >/dev/null 2>&1 || true
    nft delete table inet owm_qos_mac >/dev/null 2>&1 || true
}

qos_validate_backend() {
    command -v nft >/dev/null 2>&1 || return 1
    probe="/tmp/owm-qos-probe.$$"
    qos_generate_script "$probe" "owm_qos_probe_$$"
    nft -c -f "$probe" >/tmp/owm-qos-check.$$ 2>&1
    rc=$?
    rm -f "$probe" /tmp/owm-qos-check.$$
    return "$rc"
}

qos_reload() {
    command -v nft >/dev/null 2>&1 || return 1
    testfile="/tmp/owm-qos-test.$$"
    livefile="/tmp/owm-qos-live.$$"

    qos_generate_script "$testfile" "owm_qos_test_$$"
    if ! nft -c -f "$testfile" >/tmp/owm-qos-check.$$ 2>&1; then
        cat /tmp/owm-qos-check.$$ >&2
        rm -f "$testfile" "$livefile" /tmp/owm-qos-check.$$
        return 2
    fi

    qos_generate_script "$livefile" "owm_qos_mac"
    qos_delete_live_table
    if ! nft -f "$livefile" >/tmp/owm-qos-apply.$$ 2>&1; then
        cat /tmp/owm-qos-apply.$$ >&2
        rm -f "$testfile" "$livefile" /tmp/owm-qos-check.$$ /tmp/owm-qos-apply.$$
        return 3
    fi

    rm -f "$testfile" "$livefile" /tmp/owm-qos-check.$$ /tmp/owm-qos-apply.$$
    return 0
}

cmd_qos_capability() {
    installed=false
    running=false
    available=false
    detail="系统缺少 nftables"

    if command -v nft >/dev/null 2>&1; then
        installed=true
        if qos_validate_backend; then
            available=true
            running=true
            detail="原生 nftables 单设备限速可用"
        else
            detail="nftables 存在，但当前内核不支持所需的 MAC 限速规则"
        fi
    fi

    printf '{"installed":%s,"running":%s,"available":%s,"detail":' "$installed" "$running" "$available"
    q "$detail"
    printf '}\n'
}

cmd_device_qos() {
    mac="$(printf '%s' "$1" | tr a-f A-F)"
    down="$2"
    up="$3"

    valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }
    valid_num "$down" && valid_num "$up" || { echo '{"ok":false,"error":"invalid rate"}'; exit 3; }
    [ "$down" -ge 128 ] && [ "$down" -le 1000000 ] && [ "$up" -ge 128 ] && [ "$up" -le 1000000 ] || {
        echo '{"ok":false,"error":"rate out of range"}'
        exit 4
    }
    qos_validate_backend || {
        echo '{"ok":false,"error":"native nftables qos unavailable"}'
        exit 5
    }

    mkdir -p "$BASE"
    touch "$QOS_POLICIES"
    cp "$QOS_POLICIES" "$QOS_POLICIES.bak" 2>/dev/null || true
    awk -F '\t' -v m="$mac" 'toupper($1)!=toupper(m){print}' "$QOS_POLICIES" > "$QOS_POLICIES.tmp" || true
    printf '%s\t%s\t%s\n' "$mac" "$down" "$up" >> "$QOS_POLICIES.tmp"
    mv "$QOS_POLICIES.tmp" "$QOS_POLICIES"

    if ! qos_reload; then
        [ -f "$QOS_POLICIES.bak" ] && mv "$QOS_POLICIES.bak" "$QOS_POLICIES"
        qos_reload >/dev/null 2>&1 || true
        echo '{"ok":false,"error":"failed to apply nftables qos"}'
        exit 6
    fi

    rm -f "$QOS_POLICIES.bak"
    echo '{"ok":true}'
}

cmd_device_qos_clear() {
    mac="$(printf '%s' "$1" | tr a-f A-F)"
    valid_mac "$mac" || exit 2

    mkdir -p "$BASE"
    touch "$QOS_POLICIES"
    cp "$QOS_POLICIES" "$QOS_POLICIES.bak" 2>/dev/null || true
    awk -F '\t' -v m="$mac" 'toupper($1)!=toupper(m){print}' "$QOS_POLICIES" > "$QOS_POLICIES.tmp" || true
    mv "$QOS_POLICIES.tmp" "$QOS_POLICIES"

    if ! qos_reload; then
        [ -f "$QOS_POLICIES.bak" ] && mv "$QOS_POLICIES.bak" "$QOS_POLICIES"
        qos_reload >/dev/null 2>&1 || true
        echo '{"ok":false,"error":"failed to clear nftables qos"}'
        exit 3
    fi

    rm -f "$QOS_POLICIES.bak"
    echo '{"ok":true}'
}

cmd_qos_reload() {
    if qos_reload; then
        echo '{"ok":true}'
    else
        echo '{"ok":false,"error":"qos reload failed"}'
        exit 2
    fi
}

cmd_qos_stop() {
    qos_delete_live_table
    echo '{"ok":true}'
}

valid_time() {
    echo "$1" | grep -Eq '^([01][0-9]|2[0-3]):[0-5][0-9]$'
}

valid_days() {
    [ -n "$1" ] || return 1
    oldifs="$IFS"; IFS=','
    set -- $1
    IFS="$oldifs"
    [ "$#" -gt 0 ] || return 1
    for d in "$@"; do
        case "$d" in 0|1|2|3|4|5|6) ;; *) return 1 ;; esac
    done
    return 0
}

days_has() {
    list="$1"; target="$2"
    oldifs="$IFS"; IFS=','
    set -- $list
    IFS="$oldifs"
    for d in "$@"; do [ "$d" = "$target" ] && return 0; done
    return 1
}

shift_days() {
    list="$1"
    oldifs="$IFS"; IFS=','
    set -- $list
    IFS="$oldifs"
    out=""
    for d in "$@"; do
        n=$(((d + 1) % 7))
        [ -z "$out" ] && out="$n" || out="$out,$n"
    done
    printf '%s' "$out"
}

to_num() {
    n="$(printf '%s' "$1" | sed 's/^0//')"
    [ -n "$n" ] || n=0
    printf '%s' "$n"
}

schedule_row() {
    [ -f "$SCHEDULES" ] || return 0
    awk -F '\t' -v m="$1" 'toupper($1)==toupper(m){print; exit}' "$SCHEDULES" 2>/dev/null
}

regen_cron() {
    mkdir -p /etc/crontabs "$BASE"
    touch "$CRON"
    grep -v '# OWM-SCHEDULE ' "$CRON" > "$CRON.tmp" || true

    if [ -f "$SCHEDULES" ]; then
        while IFS="$(printf '\t')" read -r mac enabled days start end; do
            [ "$enabled" = "1" ] || continue
            valid_mac "$mac" && valid_days "$days" && valid_time "$start" && valid_time "$end" || continue

            shour="$(to_num "${start%:*}")"; smin="$(to_num "${start#*:}")"
            ehour="$(to_num "${end%:*}")"; emin="$(to_num "${end#*:}")"
            start_minutes=$((shour * 60 + smin))
            end_minutes=$((ehour * 60 + emin))
            end_days="$days"
            [ "$end_minutes" -le "$start_minutes" ] && end_days="$(shift_days "$days")"

            printf '%s %s * * %s /usr/bin/owm-agent schedule-block %s # OWM-SCHEDULE %s START\n' \
                "$smin" "$shour" "$days" "$mac" "$mac" >> "$CRON.tmp"
            printf '%s %s * * %s /usr/bin/owm-agent schedule-unblock %s # OWM-SCHEDULE %s END\n' \
                "$emin" "$ehour" "$end_days" "$mac" "$mac" >> "$CRON.tmp"
        done < "$SCHEDULES"
    fi

    mv "$CRON.tmp" "$CRON"
    chmod 600 "$CRON"
    /etc/init.d/cron reload >/dev/null 2>&1 || /etc/init.d/cron restart >/dev/null 2>&1 || true
}

apply_schedule_now() {
    mac="$1"; days="$2"; start="$3"; end="$4"
    day="$(date +%w)"
    now="$(date +%H%M)"
    nh="$(to_num "$(printf '%s' "$now" | cut -c1-2)")"
    nm="$(to_num "$(printf '%s' "$now" | cut -c3-4)")"
    now_minutes=$((nh * 60 + nm))
    shour="$(to_num "${start%:*}")"; smin="$(to_num "${start#*:}")"
    ehour="$(to_num "${end%:*}")"; emin="$(to_num "${end#*:}")"
    start_minutes=$((shour * 60 + smin))
    end_minutes=$((ehour * 60 + emin))
    active=false

    if [ "$end_minutes" -gt "$start_minutes" ]; then
        if days_has "$days" "$day" && [ "$now_minutes" -ge "$start_minutes" ] && [ "$now_minutes" -lt "$end_minutes" ]; then
            active=true
        fi
    else
        prev=$(((day + 6) % 7))
        if { days_has "$days" "$day" && [ "$now_minutes" -ge "$start_minutes" ]; } || \
           { days_has "$days" "$prev" && [ "$now_minutes" -lt "$end_minutes" ]; }; then
            active=true
        fi
    fi

    if [ "$active" = "true" ]; then
        /usr/bin/owm-agent schedule-block "$mac" >/dev/null 2>&1 || true
    else
        /usr/bin/owm-agent schedule-unblock "$mac" >/dev/null 2>&1 || true
    fi
}

cmd_device_schedule() {
    mac="$(printf '%s' "$1" | tr a-f A-F)"
    enabled="$2"; days="$3"; start="$4"; end="$5"
    valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }

    mkdir -p "$BASE"
    touch "$SCHEDULES"
    awk -F '\t' -v m="$mac" 'toupper($1)!=toupper(m){print}' "$SCHEDULES" > "$SCHEDULES.tmp" || true

    if [ "$enabled" = "1" ]; then
        valid_days "$days" && valid_time "$start" && valid_time "$end" || {
            rm -f "$SCHEDULES.tmp"
            echo '{"ok":false,"error":"invalid schedule"}'
            exit 3
        }
        printf '%s\t1\t%s\t%s\t%s\n' "$mac" "$days" "$start" "$end" >> "$SCHEDULES.tmp"
    fi

    mv "$SCHEDULES.tmp" "$SCHEDULES"
    regen_cron

    if [ "$enabled" = "1" ]; then
        apply_schedule_now "$mac" "$days" "$start" "$end"
    else
        /usr/bin/owm-agent schedule-unblock "$mac" >/dev/null 2>&1 || true
    fi
    echo '{"ok":true}'
}

cmd_device_policy() {
    mac="$(printf '%s' "$1" | tr a-f A-F)"
    valid_mac "$mac" || { echo '{"ok":false,"error":"invalid mac"}'; exit 2; }

    alias="$(alias_value "$mac")"
    static_ip=""
    dhcp_sec="$(find_dhcp_host_by_mac "$mac" 2>/dev/null || true)"
    [ -n "$dhcp_sec" ] && static_ip="$(uci -q get "dhcp.$dhcp_sec.ip" 2>/dev/null)"

    qos_enabled=false
    down=0
    up=0
    qos_row="$(qos_policy_row "$mac")"
    if [ -n "$qos_row" ]; then
        oldifs="$IFS"; IFS="$(printf '\t')"; set -- $qos_row; IFS="$oldifs"
        down="${2:-0}"
        up="${3:-0}"
        valid_num "$down" || down=0
        valid_num "$up" || up=0
        [ "$down" -gt 0 ] && [ "$up" -gt 0 ] && qos_enabled=true
    fi

    schedule_enabled=false
    days="1,2,3,4,5"
    start="22:00"
    end="07:00"
    sched="$(schedule_row "$mac")"
    if [ -n "$sched" ]; then
        oldifs="$IFS"; IFS="$(printf '\t')"; set -- $sched; IFS="$oldifs"
        [ "${2:-0}" = "1" ] && schedule_enabled=true
        days="${3:-1,2,3,4,5}"
        start="${4:-22:00}"
        end="${5:-07:00}"
    fi

    printf '{"mac":'; q "$mac"
    printf ',"alias":'; q "$alias"
    printf ',"static_ip":'; q "$static_ip"
    printf ',"qos_enabled":%s,"download_kbps":%s,"upload_kbps":%s' "$qos_enabled" "$down" "$up"
    printf ',"schedule":{"enabled":%s,"weekdays":[' "$schedule_enabled"
    first=1
    oldifs="$IFS"; IFS=','
    set -- $days
    IFS="$oldifs"
    for d in "$@"; do
        [ "$first" = "1" ] || printf ','
        first=0
        printf '%s' "$d"
    done
    printf '],"start_time":'; q "$start"
    printf ',"end_time":'; q "$end"
    printf '}}\n'
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
        if command -v fw4 >/dev/null 2>&1; then
            fw4 reload >/dev/null 2>&1 || true
        else
            /etc/init.d/firewall restart >/dev/null 2>&1 || true
        fi
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

valid_rule_index() {
    valid_num "$1" && [ "$1" -ge 0 ] && [ "$1" -le 999 ]
}

valid_port_spec() {
    echo "$1" | grep -Eq '^[0-9]{1,5}(-[0-9]{1,5})?$' || return 1
    first="${1%%-*}"
    last="${1##*-}"
    [ "$first" -ge 1 ] && [ "$first" -le 65535 ] && [ "$last" -ge 1 ] && [ "$last" -le 65535 ]
}

normalize_proto_read() {
    case "$1" in
        "tcp udp"|"udp tcp"|"tcpudp") echo "tcpudp" ;;
        udp) echo "udp" ;;
        *) echo "tcp" ;;
    esac
}

normalize_proto_write() {
    case "$1" in
        tcpudp) echo "tcp udp" ;;
        udp) echo "udp" ;;
        *) echo "tcp" ;;
    esac
}

cmd_firewall_list() {
    printf '{"zones":['
    first=1
    for sec in $(uci -q show firewall 2>/dev/null | sed -n 's/^firewall\.\(@zone\[[0-9][0-9]*\]\)=zone$/\1/p'); do
        [ "$first" = "1" ] || printf ','
        first=0
        name="$(uci_get firewall.$sec.name)"
        input="$(uci_get firewall.$sec.input)"
        output="$(uci_get firewall.$sec.output)"
        forward="$(uci_get firewall.$sec.forward)"
        masq="$(uci_get firewall.$sec.masq)"; [ "$masq" = "1" ] && masq=true || masq=false
        mtu="$(uci_get firewall.$sec.mtu_fix)"; [ "$mtu" = "1" ] && mtu=true || mtu=false
        printf '{"name":'; q "$name"
        printf ',"input":'; q "$input"
        printf ',"output":'; q "$output"
        printf ',"forward":'; q "$forward"
        printf ',"masquerading":%s,"mtu_fix":%s,"networks":[' "$masq" "$mtu"
        nf=1
        for n in $(uci -q get "firewall.$sec.network" 2>/dev/null); do
            [ "$nf" = "1" ] || printf ','
            nf=0
            q "$n"
        done
        printf ']}'
    done

    printf '],"redirects":['
    first=1
    idx=0
    for sec in $(uci -q show firewall 2>/dev/null | sed -n 's/^firewall\.\(@redirect\[[0-9][0-9]*\]\)=redirect$/\1/p'); do
        [ "$first" = "1" ] || printf ','
        first=0
        enabled="$(uci_get firewall.$sec.enabled)"; [ "$enabled" = "0" ] && enabled=false || enabled=true
        proto="$(normalize_proto_read "$(uci_get firewall.$sec.proto)")"
        printf '{"index":%s,"name":' "$idx"; q "$(uci_get firewall.$sec.name)"
        printf ',"enabled":%s,"src":' "$enabled"; q "$(uci_get firewall.$sec.src)"
        printf ',"src_port":'; q "$(uci_get firewall.$sec.src_dport)"
        printf ',"dest":'; q "$(uci_get firewall.$sec.dest)"
        printf ',"dest_ip":'; q "$(uci_get firewall.$sec.dest_ip)"
        printf ',"dest_port":'; q "$(uci_get firewall.$sec.dest_port)"
        printf ',"proto":'; q "$proto"
        printf '}'
        idx=$((idx + 1))
    done

    printf '],"rules":['
    first=1
    idx=0
    for sec in $(uci -q show firewall 2>/dev/null | sed -n 's/^firewall\.\(@rule\[[0-9][0-9]*\]\)=rule$/\1/p'); do
        [ "$first" = "1" ] || printf ','
        first=0
        enabled="$(uci_get firewall.$sec.enabled)"; [ "$enabled" = "0" ] && enabled=false || enabled=true
        printf '{"index":%s,"name":' "$idx"; q "$(uci_get firewall.$sec.name)"
        printf ',"enabled":%s,"src":' "$enabled"; q "$(uci_get firewall.$sec.src)"
        printf ',"dest":'; q "$(uci_get firewall.$sec.dest)"
        printf ',"proto":'; q "$(uci_get firewall.$sec.proto)"
        printf ',"src_port":'; q "$(uci_get firewall.$sec.src_port)"
        printf ',"dest_port":'; q "$(uci_get firewall.$sec.dest_port)"
        printf ',"target":'; q "$(uci_get firewall.$sec.target)"
        printf '}'
        idx=$((idx + 1))
    done
    printf ']}\n'
}

cmd_firewall_add_redirect() {
    name="$(decode_b64 "$1")"
    src_port="$2"
    dest_ip="$3"
    dest_port="$4"
    proto="$(normalize_proto_write "$5")"
    enabled="$6"

    [ -n "$name" ] || name="OpenWrt Manager"
    valid_port_spec "$src_port" && valid_ipv4 "$dest_ip" && valid_port_spec "$dest_port" || {
        echo '{"ok":false,"error":"invalid redirect"}'
        exit 2
    }

    sec="$(uci add firewall redirect)"
    uci set "firewall.$sec.name=$name"
    uci set "firewall.$sec.src=wan"
    uci set "firewall.$sec.src_dport=$src_port"
    uci set "firewall.$sec.dest=lan"
    uci set "firewall.$sec.dest_ip=$dest_ip"
    uci set "firewall.$sec.dest_port=$dest_port"
    uci set "firewall.$sec.proto=$proto"
    uci set "firewall.$sec.target=DNAT"
    [ "$enabled" = "1" ] && uci set "firewall.$sec.enabled=1" || uci set "firewall.$sec.enabled=0"
    uci commit firewall
    echo '{"ok":true}'
}

cmd_firewall_set_redirect() {
    idx="$1"
    name="$(decode_b64 "$2")"
    src_port="$3"
    dest_ip="$4"
    dest_port="$5"
    proto="$(normalize_proto_write "$6")"
    enabled="$7"

    valid_rule_index "$idx" || exit 2
    sec="@redirect[$idx]"
    [ "$(uci -q get "firewall.$sec")" = "redirect" ] || { echo '{"ok":false,"error":"redirect not found"}'; exit 3; }
    valid_port_spec "$src_port" && valid_ipv4 "$dest_ip" && valid_port_spec "$dest_port" || {
        echo '{"ok":false,"error":"invalid redirect"}'
        exit 4
    }

    uci set "firewall.$sec.name=$name"
    uci set "firewall.$sec.src=wan"
    uci set "firewall.$sec.src_dport=$src_port"
    uci set "firewall.$sec.dest=lan"
    uci set "firewall.$sec.dest_ip=$dest_ip"
    uci set "firewall.$sec.dest_port=$dest_port"
    uci set "firewall.$sec.proto=$proto"
    uci set "firewall.$sec.target=DNAT"
    [ "$enabled" = "1" ] && uci set "firewall.$sec.enabled=1" || uci set "firewall.$sec.enabled=0"
    uci commit firewall
    echo '{"ok":true}'
}

cmd_firewall_delete_redirect() {
    idx="$1"
    valid_rule_index "$idx" || exit 2
    sec="@redirect[$idx]"
    [ "$(uci -q get "firewall.$sec")" = "redirect" ] || { echo '{"ok":false,"error":"redirect not found"}'; exit 3; }
    uci delete "firewall.$sec"
    uci commit firewall
    echo '{"ok":true}'
}

cmd_firewall_toggle_rule() {
    idx="$1"
    enabled="$2"
    valid_rule_index "$idx" || exit 2
    sec="@rule[$idx]"
    [ "$(uci -q get "firewall.$sec")" = "rule" ] || { echo '{"ok":false,"error":"rule not found"}'; exit 3; }
    [ "$enabled" = "1" ] && uci set "firewall.$sec.enabled=1" || uci set "firewall.$sec.enabled=0"
    uci commit firewall
    echo '{"ok":true}'
}


cmd_backup_create() {
    command -v sysupgrade >/dev/null 2>&1 || { echo '{"ok":false,"error":"sysupgrade not found"}'; exit 2; }

    host="$(uci -q get system.@system[0].hostname 2>/dev/null)"
    [ -n "$host" ] || host="openwrt"
    safe_host="$(printf '%s' "$host" | tr -cd 'A-Za-z0-9_.-')"
    [ -n "$safe_host" ] || safe_host="openwrt"
    stamp="$(date +%Y%m%d-%H%M%S)"
    path="/tmp/OpenWrtManager-${safe_host}-${stamp}.tar.gz"
    includes=true

    if sysupgrade -k -b "$path" >/tmp/owm-backup.log 2>&1; then
        :
    else
        includes=false
        rm -f "$path"
        sysupgrade -b "$path" >/tmp/owm-backup.log 2>&1 || {
            tail -n 30 /tmp/owm-backup.log >&2
            exit 3
        }
    fi

    size="$(wc -c < "$path" 2>/dev/null | tr -d ' ')"
    printf '{"ok":true,"path":'; q "$path"
    printf ',"filename":'; q "OpenWrtManager-${safe_host}-${stamp}.tar.gz"
    printf ',"size_bytes":%s,"includes_package_list":%s}\n' "${size:-0}" "$includes"
}

cmd_backup_restore() {
    path="${1:-}"
    case "$path" in
        /tmp/owm-restore-*.tar.gz) ;;
        *) echo '{"ok":false,"error":"invalid restore path"}'; exit 2 ;;
    esac

    [ -s "$path" ] || { echo '{"ok":false,"error":"backup file missing"}'; exit 3; }
    tar -tzf "$path" >/tmp/owm-restore-list.log 2>&1 || {
        rm -f "$path"
        echo '{"ok":false,"error":"invalid backup archive"}'
        exit 4
    }

    if grep -Eq '(^/|(^|/)\.\.(/|$))' /tmp/owm-restore-list.log; then
        rm -f "$path"
        echo '{"ok":false,"error":"unsafe backup paths"}'
        exit 5
    fi

    grep -q '^etc/config/' /tmp/owm-restore-list.log || {
        rm -f "$path"
        echo '{"ok":false,"error":"not an OpenWrt config backup"}'
        exit 6
    }

    if sysupgrade -r "$path" >/tmp/owm-restore.log 2>&1; then
        rm -f "$path"
        echo '{"ok":true,"reboot_required":true}'
    else
        tail -n 30 /tmp/owm-restore.log >&2
        rm -f "$path"
        exit 7
    fi
}

cmd_backup_delete() {
    path="${1:-}"
    case "$path" in
        /tmp/OpenWrtManager-*.tar.gz|/tmp/owm-restore-*.tar.gz) rm -f "$path" ;;
        *) exit 2 ;;
    esac
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
        firewall)
            (
                sleep 1
                if command -v fw4 >/dev/null 2>&1; then
                    fw4 reload >/dev/null 2>&1 || /etc/init.d/firewall restart >/dev/null 2>&1 || true
                else
                    /etc/init.d/firewall restart >/dev/null 2>&1 || true
                fi
            ) >/dev/null 2>&1 &
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
    device-policy) cmd_device_policy "$2" ;;
    device-alias) cmd_device_alias "$2" "$3" ;;
    device-static-ip) cmd_device_static_ip "$2" "$3" ;;
    qos-capability) cmd_qos_capability ;;
    device-qos) cmd_device_qos "$2" "$3" "$4" "$5" ;;
    device-qos-clear) cmd_device_qos_clear "$2" ;;
    qos-reload) cmd_qos_reload ;;
    qos-stop) cmd_qos_stop ;;
    device-schedule) cmd_device_schedule "$2" "$3" "$4" "$5" "$6" ;;
    safe-begin) cmd_safe_begin "$2" "$3" ;;
    safe-status) cmd_safe_status ;;
    safe-confirm) cmd_safe_confirm "$2" ;;
    safe-rollback) cmd_safe_rollback "$2" ;;
    set-wifi) cmd_set_wifi "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" ;;
    set-lan) cmd_set_lan "$2" "$3" ;;
    set-dhcp) cmd_set_dhcp "$2" "$3" "$4" ;;
    set-dns) cmd_set_dns "$2" "$3" ;;
    set-wan) cmd_set_wan "$2" "$3" "$4" "$5" "$6" "$7" "$8" ;;
    set-wan6) cmd_set_wan6 "$2" ;;
    firewall-list) cmd_firewall_list ;;
    firewall-add-redirect) cmd_firewall_add_redirect "$2" "$3" "$4" "$5" "$6" "$7" ;;
    firewall-set-redirect) cmd_firewall_set_redirect "$2" "$3" "$4" "$5" "$6" "$7" "$8" ;;
    firewall-delete-redirect) cmd_firewall_delete_redirect "$2" ;;
    firewall-toggle-rule) cmd_firewall_toggle_rule "$2" "$3" ;;
    backup-create) cmd_backup_create ;;
    backup-restore) cmd_backup_restore "$2" ;;
    backup-delete) cmd_backup_delete "$2" ;;
    apply) cmd_apply "$2" ;;
    *) echo '{"error":"unknown command"}'; exit 2 ;;
esac
