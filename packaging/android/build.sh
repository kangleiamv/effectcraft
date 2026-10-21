#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP="$ROOT/apps/effectcraft-android/android/app"
PROJECT="$ROOT/apps/effectcraft-android/android"
TARGET_ROOT="${CARGO_TARGET_DIR:-$ROOT/target}"
HOST_LIB="$TARGET_ROOT/release/libeffectcraft_android_bridge.so"

if ! command -v java >/dev/null 2>&1; then
  printf 'error: java is required to build the native Android app\n' >&2
  exit 1
fi

JAVA_BIN="$(command -v java)"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi
if [[ ! -x "$JAVA_BIN" ]]; then
  printf 'error: JAVA_HOME does not contain a java executable\n' >&2
  exit 1
fi
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ "$JAVA_MAJOR" != "17" && "$JAVA_MAJOR" != "21" ]]; then
  printf 'error: Java 17 or 21 is required (found %s)\n' "${JAVA_MAJOR:-unknown}" >&2
  exit 1
fi

for tool in cargo cargo-ndk; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'error: %s is required to build the native Android app\n' "$tool" >&2
    exit 1
  fi
done

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
SDK="$ANDROID_HOME"
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  printf 'error: set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK\n' >&2
  exit 1
fi

cd "$ROOT"
cargo build --locked --release -p effectcraft-android-bridge
cargo run --quiet --bin uniffi-bindgen -- generate \
  --library "$HOST_LIB" \
  --language kotlin \
  --out-dir "$APP/build/generated/uniffi" \
  --config "$ROOT/apps/effectcraft-android/uniffi.toml"
cargo ndk -t arm64-v8a -o "$APP/src/main/jniLibs" build --locked --release -p effectcraft-android-bridge
"$PROJECT/gradlew" -p "$PROJECT" --no-daemon assembleDebug

OUT="$ROOT/target/android/effectcraft-native.apk"
mkdir -p "$(dirname "$OUT")"
install -m 644 "$APP/build/outputs/apk/debug/app-debug.apk" "$OUT"
printf 'Built %s\n' "$OUT"
