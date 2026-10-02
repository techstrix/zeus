# Zeus — on-device Whisper dictation for Android TV

Replaces the platform's speech-to-text engine with `whisper.cpp` running locally
on the TV. Any app that calls `SpeechRecognizer.createSpeechRecognizer(context)`
gets Whisper instead of the built-in recogniser: press mic, talk, text lands.

No cloud calls, no accounts. The model runs on the TV's CPU.

---

## Read this first: what this does and does not do on Google TV

If your TV runs **Google TV** (Shield, Chromecast with Google TV, most modern
sets), the Google Assistant is a `VoiceInteractionService` holding
`ROLE_ASSISTANT`. That is a separate mechanism from the speech recogniser and it
outranks it.

| | Works | Does not work |
|---|---|---|
| Third-party TV apps using `SpeechRecognizer` / `RecognizerIntent` | **yes** | |
| Platform components that use the default recogniser | **yes** | |
| Google Assistant, launcher mic button, "Hey Google" | | **no** — unreachable |
| Apps that hardcode `com.google.android.tts` | | no — they bypass the default |
| Apps targeting API 30+ that omit `<queries>` for `RecognitionService` | bound anyway, but they may hide their own mic button | |

The Assistant cannot be replaced without becoming `ROLE_ASSISTANT`, which Google
restricts to preinstalled assistants. That is a platform limit, not a missing
feature here.

On an **AOSP Android TV** box (Onix, ADT-3, Nokia Streaming Box, most Chinese
boxes) the same build has nothing in its way, and there the mic genuinely is
yours.

---

## Why it is built this way

Two facts, both verified against AOSP source rather than assumed, drive the
whole design.

### 1. The default recogniser is a single secure setting, and holding it is enough

`SpeechRecognizer` → `RecognitionManager` → `SpeechRecognitionManagerServiceImpl`
resolves the service from `Settings.Secure.VOICE_RECOGNITION_SERVICE` and gates
every client on `checkPrivilege()`:

```java
// frameworks/base: services/core/java/com/android/server/speech/
//                  SpeechRecognitionManagerServiceImpl.java:357
private boolean checkPrivilege(@NonNull ComponentName serviceComponent) {
    final ComponentName defaultComponent = getDefaultRecognitionServiceComponent();
    final ComponentName onDeviceComponent = getOnDeviceComponentNameLocked();
    final boolean        preinstalled     = isPreinstalledApp(serviceComponent);
    return serviceComponent.equals(defaultComponent)
        || serviceComponent.equals(onDeviceComponent)
        || preinstalled;
}
```

Being the default is enough — no system app, no root. It also decides the bind
flags, which is what makes the microphone work at all:

```java
// RemoteSpeechRecognitionService.java:90
private static int getBindingFlags(boolean isPrivileged) {
    int bindingFlags = Context.BIND_AUTO_CREATE;
    if (isPrivileged) {
        bindingFlags |= Context.BIND_INCLUDE_CAPABILITIES | Context.BIND_FOREGROUND_SERVICE;
    }
    return bindingFlags;
}
```

`BIND_INCLUDE_CAPABILITIES` is what satisfies Android 10+'s *while-in-use*
`RECORD_AUDIO` check for a plain sideloaded app. The bind is also by explicit
component, so it is exempt from package-visibility filtering.

### 2. There is no picker for this on TV

Phones have `Settings → Default apps → Assist app`; the code that writes the
setting is `packages/apps/Settings/src/com/android/settings/language/DefaultVoiceInputPicker.java`.
Android TV's settings app (`packages/apps/TvSettings`) contains **no** reference
to `VOICE_RECOGNITION_SERVICE`, `RecognitionService` or `RecognizerIntent` — it
is a separate repo with no equivalent screen, and AOSP no longer ships any
activity for `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`.

So the app has to make itself the default. `WRITE_SECURE_SETTINGS` is
`signature|privileged|development|role|installer`, and that `development` flag is
why `adb shell pm grant` can hand it to a sideloaded app.

---

## Install

### Just testing on the TV

Prebuilt APKs live in [`releases/`](releases/README.md) so no toolchain is needed:

```bash
curl -LO https://github.com/techstrix/zeus/raw/main/releases/zeus-0.1.0.apk
adb install -r zeus-0.1.0.apk
adb shell pm grant com.xorbi.zeus android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure voice_recognition_service com.xorbi.zeus/.ZeusRecognitionService
```

### Building from source

```bash
./gradlew assembleRelease          # fetches whisper.cpp + the model on first run
./scripts/install-to-tv.sh          # install, grant, set as default
```

The script runs:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.xorbi.zeus android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure voice_recognition_service com.xorbi.zeus/.ZeusRecognitionService
```

Requires JDK 21 (with `javac`), Android SDK 36 and NDK `28.2.13676358`.

Revert with:

```bash
adb shell settings delete secure voice_recognition_service
```

Then open **Zeus Dictation** from the TV launcher: it shows whether Zeus is
really the default (re-read every resume, because Google can silently write the
setting back), lets you switch model, and runs a dictation test through the real
service with latency numbers.

---

## Two things that will bite you if they are not deliberate

### Do not add `android:permission="android.permission.BIND_RECOGNITION_SERVICE"`

That permission is **not declared anywhere in AOSP** — `frameworks/base`'s
manifest defines 130 other `BIND_*` permissions but not this one. Google defines
it in their own app with `protectionLevel="signature"`. An undefined permission
can never be held, and `system_server` is the one binding us, so declaring it
would make it impossible for the platform to bind Zeus at all.

The service is exported without a permission, as AOSP's own automotive voice
guide does. The real gate is `checkPrivilege()` above; `ZeusRecognitionService.isAllowed()`
mirrors it on our side.

### A decode costs the same whatever the audio length

Measured on this engine (`tools/hosttest/scaling.cpp`):

```
  audio     wall time    per call
  0.5 s        9.84 s       9.26 s
  2.0 s        8.70 s       8.43 s
  8.0 s        9.34 s       9.42 s
  11.0 s      10.97 s       9.45 s
```

Flat. Every utterance is padded to a 30-second encoder context, so the fixed
cost dominates. Two consequences:

1. Decoding only the *new* audio (`offset_ms`/`duration_ms`) saves nothing and
   costs accuracy at the seams. Zeus does not use it. There is no offset/duration
   parameter in the engine at all.
2. **The number of decodes is the only cost that matters.** Re-decoding the whole
   utterance every 500 ms cost **158.91 s for 12 decodes** on a 2015 laptop. So
   partials are paced off how long a decode actually took, not off a fixed tick,
   and they fire on detected pauses. `RecognitionSession.decodeIntervalMs()` is
   the throttle.

Your TV will be faster than that test box, but the shape of the problem is the
same: expect partials roughly every 2–3 s of speech, and seconds of latency at
the end of an utterance while the final pass runs. That final pass costs exactly
as much as a partial, so there is no reason to skip it.

---

## Layout

```
app/src/main/cpp/
  zeus_transcriber.{h,cpp}   the engine; plain C++, no JNI, no Android types
  zeus_jni.cpp               thin JNI shim over it
  whisper.cpp/               fetched at the pinned commit by ./gradlew fetchWhisperCpp
tools/hosttest/
  main.cpp                   accuracy + partial-strategy comparison, no device needed
  scaling.cpp                the cost measurement above
releases/                    prebuilt APKs
scripts/install-to-tv.sh
```

`zeus_transcriber.cpp` deliberately has no JNI in it, which is why the host
harness can compile and exercise *the same source the APK ships*:

```bash
cmake -S tools/hosttest -B /tmp/zeus-host
cmake --build /tmp/zeus-host -j
LD_LIBRARY_PATH=/tmp/zeus-host /tmp/zeus-host/hosttest \
    app/src/main/assets/models/ggml-tiny.en-q5_1.bin \
    app/src/main/cpp/whisper.cpp/samples/jfk.wav
```

---

## Models

Bundled: `tiny.en` q5_1, 31 MB, in assets so it works offline immediately.
Downloadable from the setup screen: `base.en` q5_1 (57 MB), `small.en` q5_1
(181 MB). Bigger is more accurate but a decode is the same cost, so the trade is
accuracy against how far behind the transcript runs.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest    # endpointer state machine, RMS, resampler
```

16 JVM tests covering the parts most likely to break silently: end-of-speech
timing, too-short-utterance rejection, dBFS calibration, and resampler decimation.

---

## Known gaps

- **The endpointer is energy-based.** A TV playing in the background holds it open.
  whisper.cpp ships a silero VAD (`whisper_vad_detect_speech_no_reset`) that
  fixes this for a ~2 MB model; not wired up.
- **English only.** `resolveLanguage()` takes the caller's BCP-47 tag and maps it
  to Whisper's language code, but only the `.en` models are offered.
- **No `RecognizerIntent` activity.** Nothing on TV resolves
  `ACTION_RECOGNIZE_SPEECH` any more, and the service already covers callers that
  use `SpeechRecognizer` directly.
- **16 KB page-size devices** (2023+ TV hardware) are untested. ggml's allocators
  are 16 KB-capable in current releases and this is sideloaded rather than
  Play-distributed, but it is unverified.