# wispr-droid

Push-to-talk offline voice dictation for Android, built directly on Termux without Gradle.

Hold **Right Ctrl** on a connected keyboard to record audio, release to transcribe offline with `whisper.cpp` (via Rust JNI) and paste directly into the focused input field.

## Features

- **Zero idle RAM overhead**: Loads the Whisper model on release, transcribes 16 kHz mono PCM, and immediately frees native pages (`mallopt`) after each dictation.
- **Audio focus handling**: Pauses active media playback while recording and resumes when finished.
- **No Gradle required**: Builds the Rust native library (`libwispr.so`) and packages the APK directly on Termux using `aapt2`, `javac`, `d8`, `zipalign`, and `apksigner`.

## Requirements (Termux)

```bash
pkg install rust openjdk-17 aapt2 d8 apksigner zip cmake android-tools
```

## Build & Install

```bash
# Build build/wispr.apk
scripts/build.sh

# Build, install via ADB, push Whisper model, and enable Accessibility Service
scripts/build.sh --install
```

## Layout

| Path | Purpose |
|---|---|
| `AndroidManifest.xml` | Android app manifest |
| `src/dev/wispr/Svc.java` | Accessibility service (key handling, audio capture, clipboard paste) |
| `res/` | Accessibility service configuration and string resources |
| `rust/` | JNI wrapper around `whisper-rs` (`libwispr.so`) |
| `scripts/build.sh` | Gradle-free Termux build & ADB install script |
