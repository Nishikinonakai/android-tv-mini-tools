#!/usr/bin/env bash
#
# Build + deploy Mini Home as a PRIVILEGED system app — no reboot.
#
# Why it's not just `adb install`: the launcher needs three signature|privileged
# permissions (FORCE_STOP_PACKAGES, CHANGE_COMPONENT_ENABLED_STATE,
# REAL_GET_TASKS). On this build (ro.control_privapp_permissions=enforce) those
# only grant to an apk whose /system/priv-app base declares them — a /data
# update alone can't gain them. So we refresh the /system base first, THEN
# `adb install -r` (which both activates the build and triggers the rescan that
# grants the perms from the allowlist).
#
# ONE-TIME prerequisite (already done on the demo TV, survives reboots):
#   adb push privapp-permissions-com.zy.tvhome.xml /data/local/tmp/
#   adb shell "su 0 mount -o rw,remount / && \
#     su 0 cp /data/local/tmp/privapp-permissions-com.zy.tvhome.xml /system/etc/permissions/ && \
#     su 0 restorecon /system/etc/permissions/privapp-permissions-com.zy.tvhome.xml"
#
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
APK=app/build/outputs/apk/debug/app-debug.apk

echo "▶ build";  ./gradlew :app:assembleDebug -q

echo "▶ refresh /system/priv-app base (so the privileged perms can grant)"
adb push "$APK" /data/local/tmp/MiniHome.apk
adb shell "su 0 mount -o rw,remount /"
adb shell "su 0 cp /data/local/tmp/MiniHome.apk /system/priv-app/MiniHome/MiniHome.apk"
adb shell "su 0 chown root:root /system/priv-app/MiniHome/MiniHome.apk; \
           su 0 chmod 644   /system/priv-app/MiniHome/MiniHome.apk; \
           su 0 restorecon  /system/priv-app/MiniHome/MiniHome.apk"

echo "▶ install (activates build + triggers the priv-perm grant)"
adb install -r "$APK"

echo "▶ granted privileged perms:"
adb shell "dumpsys package com.zy.tvhome | grep -E 'FORCE_STOP_PACKAGES|CHANGE_COMPONENT_ENABLED_STATE|REAL_GET_TASKS' | grep -i granted | sort -u"
echo "✓ done"
