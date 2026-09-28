#!/usr/bin/env sh
set -eu
KEYSTORE="${1:-release-signing/openwrt-manager-release.jks}"
base64 "$KEYSTORE" | tr -d '\n'
printf '\n'
