#!/usr/bin/env bash
# Verify the invariants that make an APK a Slide release rather than merely a successful build.
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 APK VERSION VERSION_CODE UNSIGNED|CERT_SHA256" >&2
    exit 2
fi

APK="$1"
EXPECTED_VERSION="$2"
EXPECTED_VERSION_CODE="$3"
EXPECTED_SIGNER="${4,,}"
EXPECTED_PACKAGE="com.slide"
EXPECTED_MIN_SDK="26"
EXPECTED_TARGET_SDK="36"
EXPECTED_BUILD_TOOLS="36.0.0"
MODEL_PATH="assets/ggml-base.en-q5_1.bin"
NOTICES_PATH="assets/THIRD_PARTY_NOTICES.txt"
SWIPE_LICENSE_PATH="assets/swipe/FUTO_MODEL_LICENSE.md"
SWIPE_ENCODER_PATH="assets/swipe/encoder.pte"
SWIPE_DECODER_PATH="assets/swipe/decoder.pte"
TRIGRAM_PATH="assets/trigrams_en.bin"
MODEL_SIZE="59721011"
MODEL_SHA256="4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
SWIPE_ENCODER_SHA256="725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf"
SWIPE_DECODER_SHA256="01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85"
TRIGRAM_SHA256="dd85903183a89ddd957978636e5366e1e11e2aed14ef5d8c9b13abea48dcb3e1"
EXPECTED_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
EXECUTORCH_ABIS=(arm64-v8a x86_64)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }

find_build_tool() {
    local name="$1"
    local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$sdk_root" && -f "$ROOT/local.properties" ]]; then
        sdk_root="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | sed -n '1p')"
    fi
    [[ -n "$sdk_root" ]] || { echo "ANDROID_HOME or ANDROID_SDK_ROOT is required." >&2; return 1; }
    local tool="$sdk_root/build-tools/$EXPECTED_BUILD_TOOLS/$name"
    [[ -x "$tool" ]] || {
        echo "Pinned Android build tool is missing: $tool" >&2
        return 1
    }
    printf '%s\n' "$tool"
}

AAPT="$(find_build_tool aapt)"
APKSIGNER="$(find_build_tool apksigner)"
ZIPALIGN="$(find_build_tool zipalign)"
[[ -x "$AAPT" ]] || { echo "aapt was not found." >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "apksigner was not found." >&2; exit 1; }
[[ -x "$ZIPALIGN" ]] || { echo "zipalign was not found." >&2; exit 1; }

badging="$($AAPT dump badging "$APK")"
package_name="$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
version_code="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<< "$badging")"
version_name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<< "$badging")"
min_sdk="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" <<< "$badging")"
target_sdk="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<< "$badging")"
[[ "$package_name" == "$EXPECTED_PACKAGE" ]] || {
    echo "Wrong package: expected $EXPECTED_PACKAGE, got $package_name" >&2
    exit 1
}
[[ "$version_code" == "$EXPECTED_VERSION_CODE" ]] || {
    echo "Wrong versionCode: expected $EXPECTED_VERSION_CODE, got $version_code" >&2
    exit 1
}
[[ "$version_name" == "$EXPECTED_VERSION" ]] || {
    echo "Wrong versionName: expected $EXPECTED_VERSION, got $version_name" >&2
    exit 1
}
[[ "$min_sdk" == "$EXPECTED_MIN_SDK" ]] || {
    echo "Wrong minSdk: expected $EXPECTED_MIN_SDK, got $min_sdk" >&2
    exit 1
}
[[ "$target_sdk" == "$EXPECTED_TARGET_SDK" ]] || {
    echo "Wrong targetSdk: expected $EXPECTED_TARGET_SDK, got $target_sdk" >&2
    exit 1
}
[[ "$badging" != *"application-debuggable"* ]] || {
    echo "Release APK is debuggable." >&2
    exit 1
}
"$ZIPALIGN" -c -P 16 4 "$APK"

notices_size="$(unzip -lv "$APK" | awk -v path="$NOTICES_PATH" '$NF == path { print $1; found=1 } END { if (!found) exit 1 }')"
[[ "$notices_size" =~ ^[0-9]+$ && "$notices_size" -ge 1000 ]] || {
    echo "Packaged third-party notices are missing or unexpectedly short." >&2
    exit 1
}

swipe_license_size="$(unzip -lv "$APK" | awk -v path="$SWIPE_LICENSE_PATH" '$NF == path { print $1; found=1 } END { if (!found) exit 1 }')"
[[ "$swipe_license_size" =~ ^[0-9]+$ && "$swipe_license_size" -ge 5000 ]] || {
    echo "Packaged FUTO Swipe model licence is missing or unexpectedly short." >&2
    exit 1
}

verify_stored_asset() {
    local path="$1"
    local expected_sha="$2"
    local length method actual_sha
    read -r length method < <(
        unzip -lv "$APK" | awk -v path="$path" '$NF == path { print $1, $2; found=1 } END { if (!found) exit 1 }'
    )
    [[ "$length" =~ ^[0-9]+$ && "$length" -gt 0 ]] || {
        echo "Packaged asset is missing or empty: $path" >&2
        exit 1
    }
    [[ "$method" == "Stored" ]] || {
        echo "$path must be STORED, but ZIP method is $method" >&2
        exit 1
    }
    actual_sha="$(unzip -p "$APK" "$path" | sha256sum | awk '{print $1}')"
    [[ "$actual_sha" == "$expected_sha" ]] || {
        echo "Wrong SHA-256 for $path: expected $expected_sha, got $actual_sha" >&2
        exit 1
    }
}

verify_stored_asset "$SWIPE_ENCODER_PATH" "$SWIPE_ENCODER_SHA256"
verify_stored_asset "$SWIPE_DECODER_PATH" "$SWIPE_DECODER_SHA256"
verify_stored_asset "$TRIGRAM_PATH" "$TRIGRAM_SHA256"

mapfile -t packaged_models < <(unzip -Z1 "$APK" | sed -n '/^assets\/ggml-.*\.bin$/p')
if [[ ${#packaged_models[@]} -ne 1 || "${packaged_models[0]}" != "$MODEL_PATH" ]]; then
    printf 'Unexpected packaged model set:\n%s\n' "${packaged_models[*]:-(none)}" >&2
    exit 1
fi

read -r model_length model_method < <(
    unzip -lv "$APK" | awk -v path="$MODEL_PATH" '$NF == path { print $1, $2; found=1 } END { if (!found) exit 1 }'
)
[[ "$model_length" == "$MODEL_SIZE" ]] || {
    echo "Wrong model size: expected $MODEL_SIZE, got $model_length" >&2
    exit 1
}
[[ "$model_method" == "Stored" ]] || {
    echo "Whisper model must be STORED, but ZIP method is $model_method" >&2
    exit 1
}
actual_model_sha="$(unzip -p "$APK" "$MODEL_PATH" | sha256sum | awk '{print $1}')"
[[ "$actual_model_sha" == "$MODEL_SHA256" ]] || {
    echo "Wrong model SHA-256: expected $MODEL_SHA256, got $actual_model_sha" >&2
    exit 1
}

mapfile -t packaged_abis < <(
    unzip -Z1 "$APK" \
        | sed -n 's#^lib/\([^/]*\)/libslide_asr\.so$#\1#p' \
        | sort
)
if [[ "${packaged_abis[*]}" != "${EXPECTED_ABIS[*]}" ]]; then
    echo "Wrong libslide_asr.so ABI set: expected ${EXPECTED_ABIS[*]}, got ${packaged_abis[*]:-(none)}" >&2
    exit 1
fi

mapfile -t executorch_abis < <(
    unzip -Z1 "$APK" \
        | sed -n 's#^lib/\([^/]*\)/libexecutorch\.so$#\1#p' \
        | sort
)
if [[ "${executorch_abis[*]}" != "${EXECUTORCH_ABIS[*]}" ]]; then
    echo "Wrong libexecutorch.so ABI set: expected ${EXECUTORCH_ABIS[*]}, got ${executorch_abis[*]:-(none)}" >&2
    exit 1
fi

if [[ "$EXPECTED_SIGNER" == "unsigned" ]]; then
    if "$APKSIGNER" verify --min-sdk-version 26 "$APK" >/dev/null 2>&1; then
        echo "The unprivileged build unexpectedly produced a signed APK." >&2
        exit 1
    fi
else
    "$APKSIGNER" verify --verbose --min-sdk-version 26 "$APK" >/dev/null
    signer="$($APKSIGNER verify --print-certs --min-sdk-version 26 "$APK" \
        | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'A-F' 'a-f')"
    [[ "$signer" == "$EXPECTED_SIGNER" ]] || {
        echo "Wrong signing certificate: expected $EXPECTED_SIGNER, got ${signer:-none}" >&2
        exit 1
    }
fi

printf 'Verified %s (%s %s, speech %s, swipe models and trigram verified, ABIs %s, signer %s)\n' \
    "$APK" "$EXPECTED_VERSION" "$EXPECTED_VERSION_CODE" "$MODEL_SHA256" \
    "${EXPECTED_ABIS[*]}" "$EXPECTED_SIGNER"
