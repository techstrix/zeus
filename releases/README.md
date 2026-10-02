# Prebuilt APKs

Ready-to-install APKs, so testing on a TV needs no JDK, Android SDK or NDK.

| File | Version | Notes |
|---|---|---|
| `zeus-0.2.0.apk` | 0.2.0 | arm64-v8a + x86_64, tiny.en bundled, minSdk 26 |

---

## The short version

Sideload the APK onto the TV. Open **Zeus Dictation**. Press **Choose Zeus as the
keyboard**. Pick **Zeus Dictation** in the list. Done.

That is the whole setup. No computer, no adb, no root.

Then in any app with a normal text field: tap the field, press **Dictate** (or the
remote's centre button), and talk.

To get the APK onto the TV, whatever is easiest: a USB stick, a file manager on the
TV, or `adb install` if you already have a machine plugged in.

---

## Optional: make Zeus the platform's speech recogniser

This is the other, deeper change: it makes *other apps' own* dictation buttons
route through Whisper, which a keyboard cannot reach.

It **cannot** be done from the APK alone. Becoming the default recogniser means
writing `Settings.Secure.VOICE_RECOGNITION_SERVICE`, which needs
`WRITE_SECURE_SETTINGS`, and an app cannot grant itself a permission.

If you have a computer connected by USB, these two commands are all it takes:

```bash
adb shell pm grant com.xorbi.zeus android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure voice_recognition_service com.xorbi.zeus/.ZeusRecognitionService
```

Check it took:

```bash
adb shell settings get secure voice_recognition_service
# com.xorbi.zeus/.ZeusRecognitionService
```

Undo:

```bash
adb shell settings delete secure voice_recognition_service
```

---

## If something is wrong

**The Dictate button never appears.** That app draws its own keyboard instead of
using the platform one — Leanback search fields and several launchers do this. No
keyboard, ours or anyone's, will show up there.

**The keyboard is not in the list.** It appears under **Settings → … → Keyboard**
as "Zeus Dictation". Make sure the APK installed; if you updated over an older
build and install failed, see the signing note below.

**"Microphone access has not been granted."** Open the app and press the mic
permission row. On some TVs the permission lives under Settings → Privacy →
Microphone instead of being granted on the prompt.

**It says it is the default but nothing changed.** Google TV's Assistant handles
its own voice separately and is not affected — that is expected, not a fault. Use
the keyboard for dictation.

---

## Signing

These are signed with a self-signed personal key that is deliberately **not** in
the repository, because this is a public repo and a committed signing key would let
anyone publish updates to the app.

Consequence: all APKs in this folder share one key, so replacing one with a later
version here works normally. But an APK you build yourself with a *different* key
will fail `adb install -r` with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstall
first (`adb uninstall com.xorbi.zeus`), which clears the app's data and settings.

## Rebuilding

See the main [`README.md`](../README.md). Signing credentials go in
`local.properties` (gitignored):

```properties
zeus.storeFile=../zeus-release.jks
zeus.storePassword=...
zeus.keyAlias=zeus
zeus.keyPassword=...
```

Without them, `assembleRelease` falls back to the standard Android debug key,
which is fine for local testing but will not match these APKs.