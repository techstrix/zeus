# Prebuilt APKs

Ready-to-install APKs, so testing on a TV does not need a JDK, Android SDK or NDK.

| File | Version | Notes |
|---|---|---|
| `zeus-0.1.0.apk` | 0.1.0 | arm64-v8a + x86_64, tiny.en q5_1 bundled, minSdk 26 |

## Install on the TV

```bash
# 1. grab it
curl -LO https://github.com/techstrix/zeus/raw/main/releases/zeus-0.1.0.apk

# 2. install and make Zeus the platform's speech recogniser
adb install -r zeus-0.1.0.apk
adb shell pm grant com.xorbi.zeus android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure voice_recognition_service com.xorbi.zeus/.ZeusRecognitionService

# 3. check it landed
adb shell settings get secure voice_recognition_service
```

Then open **Zeus Dictation** from the TV launcher. It shows whether Zeus really is
the default (re-read on every resume, because Google can silently write the
setting back to itself), lets you swap models, and runs a dictation test through
the real service with latency numbers.

Revert:

```bash
adb shell settings delete secure voice_recognition_service
```

## Signing

These are signed with a self-signed personal key that is **not** in this
repository, deliberately — it is a public repo and a committed signing key would
let anyone ship updates to the app.

That has one consequence worth knowing: if you ever rebuild and the APK is signed
with a *different* key, `adb install -r` will fail with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and you will need
`adb uninstall com.xorbi.zeus` first, which clears the app's data and settings.
The APKs here are all signed with the same key, so replacing one with a later
version from this directory works normally.

## Rebuilding

See the main `README.md`. Signing credentials live in `local.properties`
(gitignored):

```properties
zeus.storeFile=../zeus-release.jks
zeus.storePassword=...
zeus.keyAlias=zeus
zeus.keyPassword=...
```

Without those, `assembleRelease` falls back to the standard Android debug key,
which is fine for local testing but will not match these APKs.