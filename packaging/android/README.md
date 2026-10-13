# EffectCraft Android

This is a Kotlin/Jetpack Compose Android application. It calls the existing Rust
engine through UniFFI; editing commands, project data, media import, and render
logic remain in the Rust workspace. The Android interface is native Compose and
does not host the web app.

## Build a debug APK

Install Rust 1.95 or newer, the `aarch64-linux-android` Rust target, Android
SDK Platform 35, Android NDK, Java 17, `cargo-ndk`, and `uniffi-bindgen` 0.29.5.
Set `JAVA_HOME` to Java 17 or 21 and `ANDROID_HOME` or `ANDROID_SDK_ROOT`, then
run:

```sh
cargo install cargo-ndk
cargo install uniffi --version 0.29.5 --features cli --locked
packaging/android/build.sh
```

The script builds the Rust bridge for arm64, generates Kotlin bindings, assembles
the Android app, and copies the APK to `target/android/effectcraft-native.apk`.
The Gradle project is in `apps/effectcraft-android/android`.

The current native editor surface includes project/composition/layer controls,
native project and footage pickers, recovery saves, undo/redo, and a command
palette for the engine command registry. This is an initial Android frontend,
not yet full parity with the desktop and egui frontends; in particular the
composition viewer, timeline editing, audio, and render-queue presentation need
native implementations before claiming feature parity.