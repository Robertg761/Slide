#!/usr/bin/env bash
# Start an isolated emulator and run every packaged-runtime instrumentation suite.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 API_LEVEL (for example 26 or 37.0)" >&2
    exit 2
fi

API_LEVEL="$1"
[[ "$API_LEVEL" =~ ^[0-9]+([.][0-9]+)?$ ]] || {
    echo "Invalid Android API level: $API_LEVEL" >&2
    exit 2
}

# Emulator 37.1.11 can segfault during gfxstream initialization in the legacy `swiftshader` mode.
# Lavapipe is the current software-rendering path and works for the API 26 release floor; callers
# can still override it when validating a host-specific renderer.
GPU_MODE="${SLIDE_EMULATOR_GPU:-lavapipe}"
ACCEL_MODE="${SLIDE_EMULATOR_ACCEL:-on}"
MEMORY_MB="${SLIDE_EMULATOR_MEMORY_MB:-2048}"
DATA_PARTITION_MB="${SLIDE_EMULATOR_DATA_PARTITION_MB:-8192}"
[[ "$GPU_MODE" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid emulator GPU mode: $GPU_MODE" >&2; exit 2; }
[[ "$ACCEL_MODE" =~ ^(on|off|auto)$ ]] || { echo "Invalid emulator acceleration mode: $ACCEL_MODE" >&2; exit 2; }
[[ "$MEMORY_MB" =~ ^[0-9]+$ ]] || { echo "Invalid emulator memory size: $MEMORY_MB" >&2; exit 2; }
[[ "$DATA_PARTITION_MB" =~ ^[0-9]+$ ]] || {
    echo "Invalid emulator data partition size: $DATA_PARTITION_MB" >&2
    exit 2
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk[.]dir=//p' "$ROOT/local.properties" | sed -n '1p')"
fi
[[ -n "$SDK_ROOT" ]] || { echo "ANDROID_HOME or ANDROID_SDK_ROOT is required." >&2; exit 1; }

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
EMULATOR="${SLIDE_EMULATOR_BIN:-$SDK_ROOT/emulator/emulator}"
ADB="$SDK_ROOT/platform-tools/adb"
for tool in "$SDKMANAGER" "$AVDMANAGER"; do
    [[ -x "$tool" ]] || { echo "Required Android tool is missing: $tool" >&2; exit 1; }
done

IMAGE="system-images;android-$API_LEVEL;google_apis;x86_64"
sdk_packages=("platform-tools" "$IMAGE")
if [[ -z "${SLIDE_EMULATOR_BIN:-}" ]]; then
    sdk_packages+=("emulator")
fi
"$SDKMANAGER" --install "${sdk_packages[@]}" </dev/null >/dev/null
[[ -x "$EMULATOR" && -x "$ADB" ]] || { echo "The emulator toolchain was not installed." >&2; exit 1; }

TEMP_DIR="$(mktemp -d)"
EMULATOR_PID=""
SERIAL="emulator-5554"
LOCK_FILE="${XDG_RUNTIME_DIR:-/tmp}/slide-android-emulator-5554.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "Another Slide instrumentation emulator already owns $SERIAL." >&2
    rm -rf "$TEMP_DIR"
    exit 1
fi
if "$ADB" devices | awk 'NR > 1 { print $1 }' | grep -Fxq "$SERIAL"; then
    echo "Refusing to use occupied emulator serial $SERIAL; stop that device first." >&2
    rm -rf "$TEMP_DIR"
    exit 1
fi
cleanup() {
    local status=$?
    if (( status != 0 )) && [[ -n "$EMULATOR_PID" ]]; then
        mkdir -p "$ROOT/build/reports"
        "$ADB" -s "$SERIAL" logcat -d \
            >"$ROOT/build/reports/instrumentation-api-$API_LEVEL.log" 2>&1 || true
        cp "$TEMP_DIR/emulator.log" \
            "$ROOT/build/reports/emulator-api-$API_LEVEL.log" 2>/dev/null || true
    fi
    if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    fi
    if [[ -n "$EMULATOR_PID" ]]; then wait "$EMULATOR_PID" 2>/dev/null || true; fi
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

export ANDROID_AVD_HOME="$TEMP_DIR/avd"
export ANDROID_USER_HOME="$TEMP_DIR/android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
AVD_NAME="slide_ci_api_${API_LEVEL//./_}"
verify_spawned_emulator() {
    if [[ -z "$EMULATOR_PID" ]] || ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "This invocation's Android emulator process is not alive." >&2
        return 1
    fi
    local actual_avd
    actual_avd="$($ADB -s "$SERIAL" emu avd name 2>/dev/null | tr -d '\r' | sed -n '1p')"
    if [[ "$actual_avd" != "$AVD_NAME" ]]; then
        echo "Serial $SERIAL belongs to AVD ${actual_avd:-unknown}, not $AVD_NAME." >&2
        return 1
    fi
}
printf 'no\n' | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$IMAGE" \
    --device pixel_2 \
    --force >/dev/null

"$EMULATOR" \
    -avd "$AVD_NAME" \
    -port 5554 \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-metrics \
    -no-snapshot \
    -wipe-data \
    -gpu "$GPU_MODE" \
    -accel "$ACCEL_MODE" \
    -memory "$MEMORY_MB" \
    -partition-size "$DATA_PARTITION_MB" \
    >"$TEMP_DIR/emulator.log" 2>&1 &
EMULATOR_PID="$!"

timeout 180 "$ADB" -s "$SERIAL" wait-for-device
verify_spawned_emulator
luma_sampling_disabled=false
for _ in $(seq 1 30); do
    if "$ADB" -s "$SERIAL" shell setprop debug.sf.luma_sampling 0 >/dev/null 2>&1 \
        && [[ "$($ADB -s "$SERIAL" shell getprop debug.sf.luma_sampling 2>/dev/null \
            | tr -d '\r')" == "0" ]]; then
        luma_sampling_disabled=true
        break
    fi
    sleep 1
done
if [[ "$luma_sampling_disabled" != true ]]; then
    echo "Could not disable headless SurfaceFlinger luma sampling." >&2
    exit 1
fi
booted=false
for _ in $(seq 1 180); do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "Android emulator exited before boot completed:" >&2
        tail -200 "$TEMP_DIR/emulator.log" >&2
        exit 1
    fi
    if [[ "$($ADB -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        booted=true
        break
    fi
    sleep 2
done
if [[ "$booted" != true ]]; then
    echo "Android emulator did not finish booting:" >&2
    tail -200 "$TEMP_DIR/emulator.log" >&2
    exit 1
fi

settings_ready=false
for _ in $(seq 1 60); do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "Android emulator exited before the settings service became ready:" >&2
        tail -200 "$TEMP_DIR/emulator.log" >&2
        exit 1
    fi
    if "$ADB" -s "$SERIAL" shell cmd settings get global window_animation_scale \
        >/dev/null 2>&1; then
        settings_ready=true
        break
    fi
    sleep 2
done
if [[ "$settings_ready" != true ]]; then
    echo "Android settings service was not ready after boot completed:" >&2
    tail -200 "$TEMP_DIR/emulator.log" >&2
    exit 1
fi

# Android 37 Google APIs images can fill their default data partition during first boot. That can
# abort SurfaceFlinger, restart Zygote, and leave a stale sys.boot_completed=1 while package installs
# fail. Require consecutive healthy service and storage probes before running any app tests.
services_stable=false
stable_samples=0
stable_system_server_pid=""
for _ in $(seq 1 60); do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "Android emulator exited before system services stabilized:" >&2
        tail -200 "$TEMP_DIR/emulator.log" >&2
        exit 1
    fi
    available_kb="$($ADB -s "$SERIAL" shell df -k /data 2>/dev/null \
        | tr -d '\r' \
        | awk 'NR == 2 { print $4 }')"
    system_server_pid="$($ADB -s "$SERIAL" shell pidof system_server 2>/dev/null \
        | tr -d '\r' || true)"
    if [[ "$available_kb" =~ ^[0-9]+$ ]] \
        && [[ -n "$system_server_pid" ]] \
        && [[ "$system_server_pid" == "$stable_system_server_pid" ]] \
        && (( available_kb >= 524288 )) \
        && "$ADB" -s "$SERIAL" shell cmd package list packages android >/dev/null 2>&1 \
        && "$ADB" -s "$SERIAL" shell cmd activity get-config >/dev/null 2>&1 \
        && "$ADB" -s "$SERIAL" shell cmd settings get global window_animation_scale \
            >/dev/null 2>&1; then
        ((stable_samples += 1))
        if (( stable_samples >= 12 )); then
            services_stable=true
            break
        fi
    else
        stable_samples=0
        stable_system_server_pid="$system_server_pid"
    fi
    sleep 5
done
if [[ "$services_stable" != true ]]; then
    echo "Android system services or /data storage did not stabilize after boot:" >&2
    "$ADB" -s "$SERIAL" shell df -h /data >&2 || true
    tail -200 "$TEMP_DIR/emulator.log" >&2
    exit 1
fi
"$ADB" -s "$SERIAL" shell df -h /data

"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0
verify_spawned_emulator
export ANDROID_SERIAL="$SERIAL"

if ! "$ROOT/gradlew" --no-daemon \
    :asr:connectedDebugAndroidTest \
    :engine:connectedDebugAndroidTest; then
    mkdir -p "$ROOT/build/reports"
    "$ADB" -s "$SERIAL" logcat -d > "$ROOT/build/reports/instrumentation-api-$API_LEVEL.log" || true
    tail -200 "$TEMP_DIR/emulator.log" >&2
    exit 1
fi

echo "Android API $API_LEVEL packaged-runtime instrumentation passed."
