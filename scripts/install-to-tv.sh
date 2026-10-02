#!/usr/bin/env bash
#
# Installs Zeus on a TV and points the platform's speech pipeline at it.
#
# There is no picker for this on Android TV: packages/apps/Settings has a voice
# input picker under src/com/android/settings/language, but packages/apps/TvSettings
# has no equivalent. So the default is set directly, which needs WRITE_SECURE_SETTINGS.
# That permission's protection level includes `development`, so `pm grant` can hand it
# to a sideloaded app: no root, no ROM rebuild.
#
# Usage: ./scripts/install-to-tv.sh [apk] [package]
set -euo pipefail

PKG="${2:-com.xorbi.zeus}"
SERVICE="$PKG/.ZeusRecognitionService"
APK="${1:-app/build/outputs/apk/release/app-release.apk}"

if [[ ! -f "$APK" ]]; then
    echo "APK not found: $APK" >&2
    echo "Build one first:  ./gradlew assembleRelease" >&2
    exit 1
fi

echo "==> Installing $APK"
adb install -r "$APK"

echo "==> Granting WRITE_SECURE_SETTINGS so Zeus can manage its own default"
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

echo "==> Pointing voice_recognition_service at Zeus"
adb shell settings put secure voice_recognition_service "$SERVICE"

echo "==> Verifying"
echo -n "    voice_recognition_service = "
adb shell settings get secure voice_recognition_service
echo -n "    Zeus service registered   = "
if adb shell dumpsys package com.xorbi.zeus 2>/dev/null | grep -q "ZeusRecognitionService"; then
    echo "yes"
else
    echo "NO - the install did not land?"
fi

echo
echo "    To see what the platform resolves to:"
echo "      adb shell cmd package query-services -a android.speech.RecognitionService"

cat <<EOF

Done. Zeus now answers SpeechRecognizer for apps that use the platform default.

  adb shell settings delete secure voice_recognition_service   # revert

Still Google's business, and will not change:
  - the Google Assistant, the launcher mic button and "Hey Google". That is
    ROLE_ASSISTANT plus VoiceInteractionService, a separate mechanism.

Open Zeus from the TV launcher to run a dictation test and watch latency.
EOF