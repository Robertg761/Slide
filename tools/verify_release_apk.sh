#!/usr/bin/env bash
# Verify the invariants that make an APK a Slide release rather than merely a successful build.
set -euo pipefail

verify_elf_abi() {
    local file="$1"
    local abi="$2"
    local expected_class expected_machine
    case "$abi" in
        armeabi-v7a) expected_class=1; expected_machine=40 ;;
        arm64-v8a) expected_class=2; expected_machine=183 ;;
        x86) expected_class=1; expected_machine=3 ;;
        x86_64) expected_class=2; expected_machine=62 ;;
        *) echo "Unknown Android ABI directory: $abi" >&2; return 1 ;;
    esac

    local -a header
    read -r -a header <<< "$(od -An -tu1 -N20 "$file" | tr '\n' ' ')"
    if [[ ${#header[@]} -ne 20 ||
          ${header[0]} -ne 127 || ${header[1]} -ne 69 ||
          ${header[2]} -ne 76 || ${header[3]} -ne 70 ]]; then
        echo "$file is not a complete ELF header." >&2
        return 1
    fi
    if [[ ${header[5]} -ne 1 ]]; then
        echo "$file is not a little-endian Android ELF image." >&2
        return 1
    fi
    local actual_class="${header[4]}"
    local actual_machine=$((header[18] | (header[19] << 8)))
    if [[ "$actual_class" -ne "$expected_class" || "$actual_machine" -ne "$expected_machine" ]]; then
        echo "$file has ELF class $actual_class / machine $actual_machine, not ABI $abi." >&2
        return 1
    fi

    # Magic/class/machine alone can be forged by a 20-byte truncated file. Require binutils (or
    # LLVM's compatible reader) to traverse the complete headers, program table, section table,
    # and dynamic table, then require the structures an Android shared library must expose.
    local readelf_tool="${READELF:-}"
    if [[ -z "$readelf_tool" ]]; then
        readelf_tool="$(command -v llvm-readelf || command -v readelf || true)"
    fi
    if [[ -z "$readelf_tool" || ! -x "$readelf_tool" ]]; then
        echo "readelf or llvm-readelf is required for complete native verification." >&2
        return 1
    fi
    local structure
    if ! structure="$(LC_ALL=C "$readelf_tool" --wide --file-header --program-headers \
        --section-headers --dynamic "$file" 2>&1)"; then
        echo "$file is not a structurally complete ELF image:" >&2
        printf '%s\n' "$structure" >&2
        return 1
    fi
    if grep -Eq '(^|[[:space:]])(Error|Warning):' <<< "$structure" ||
       ! grep -Eq '^[[:space:]]*Type:[[:space:]]+DYN' <<< "$structure" ||
       ! grep -Eq '^[[:space:]]*LOAD[[:space:]]' <<< "$structure" ||
       ! grep -Eq '^[[:space:]]*DYNAMIC[[:space:]]' <<< "$structure" ||
       ! grep -Eq '^Dynamic section at offset ' <<< "$structure"; then
        echo "$file is not a complete loadable dynamic ELF image." >&2
        return 1
    fi
}

EXPECTED_NATIVE_PATHS=(
    lib/arm64-v8a/libandroidx.graphics.path.so
    lib/arm64-v8a/libc++_shared.so
    lib/arm64-v8a/libdatastore_shared_counter.so
    lib/arm64-v8a/libexecutorch.so
    lib/arm64-v8a/libfbjni.so
    lib/arm64-v8a/libslide_asr.so
    lib/armeabi-v7a/libandroidx.graphics.path.so
    lib/armeabi-v7a/libc++_shared.so
    lib/armeabi-v7a/libdatastore_shared_counter.so
    lib/armeabi-v7a/libfbjni.so
    lib/armeabi-v7a/libslide_asr.so
    lib/x86/libandroidx.graphics.path.so
    lib/x86/libc++_shared.so
    lib/x86/libdatastore_shared_counter.so
    lib/x86/libfbjni.so
    lib/x86/libslide_asr.so
    lib/x86_64/libandroidx.graphics.path.so
    lib/x86_64/libc++_shared.so
    lib/x86_64/libdatastore_shared_counter.so
    lib/x86_64/libexecutorch.so
    lib/x86_64/libfbjni.so
    lib/x86_64/libslide_asr.so
)
EXPECTED_RUNTIME_DATA_PATHS=(
    assets/bigrams_en.bin
    assets/emoji.bin
    assets/ggml-base.en-q5_1.bin
    assets/lexicon_en.bin
    assets/swipe/decoder.pte
    assets/swipe/encoder.pte
    assets/trigrams_en.bin
)

verify_exact_manifest() {
    local label="$1"
    local expected_name="$2"
    local actual_name="$3"
    local -n expected_ref="$expected_name"
    local -n actual_ref="$actual_name"
    if [[ "${actual_ref[*]}" != "${expected_ref[*]}" ]]; then
        printf 'Wrong %s manifest.\nExpected:\n%s\nActual:\n%s\n' \
            "$label" "${expected_ref[*]:-(none)}" "${actual_ref[*]:-(none)}" >&2
        return 1
    fi
}

# Focused test seam for release-script fixtures; ordinary release verification uses four args.
if [[ $# -eq 3 && "$1" == "--verify-elf-abi" ]]; then
    verify_elf_abi "$2" "$3"
    exit
fi
if [[ $# -eq 1 && "$1" == "--print-expected-native-manifest" ]]; then
    printf '%s\n' "${EXPECTED_NATIVE_PATHS[@]}"
    exit
fi
if [[ $# -eq 1 && "$1" == "--print-expected-runtime-data-manifest" ]]; then
    printf '%s\n' "${EXPECTED_RUNTIME_DATA_PATHS[@]}"
    exit
fi
if [[ $# -eq 2 && "$1" == "--verify-native-manifest" ]]; then
    # Read indirectly through verify_exact_manifest's nameref.
    # shellcheck disable=SC2034
    mapfile -t fixture_paths < <(LC_ALL=C sort "$2")
    verify_exact_manifest "native library" EXPECTED_NATIVE_PATHS fixture_paths
    exit
fi
if [[ $# -eq 2 && "$1" == "--verify-runtime-data-manifest" ]]; then
    # Read indirectly through verify_exact_manifest's nameref.
    # shellcheck disable=SC2034
    mapfile -t fixture_paths < <(LC_ALL=C sort "$2")
    verify_exact_manifest "runtime model/data" EXPECTED_RUNTIME_DATA_PATHS fixture_paths
    exit
fi

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
EXPECTED_TARGET_SDK="37"
EXPECTED_BUILD_TOOLS="36.0.0"
MODEL_PATH="assets/ggml-base.en-q5_1.bin"
NOTICES_PATH="assets/THIRD_PARTY_NOTICES.txt"
SWIPE_LICENSE_PATH="assets/swipe/FUTO_MODEL_LICENSE.md"
SWIPE_ENCODER_PATH="assets/swipe/encoder.pte"
SWIPE_DECODER_PATH="assets/swipe/decoder.pte"
EMOJI_PATH="assets/emoji.bin"
LEXICON_PATH="assets/lexicon_en.bin"
BIGRAM_PATH="assets/bigrams_en.bin"
TRIGRAM_PATH="assets/trigrams_en.bin"
MODEL_SIZE="59721011"
MODEL_SHA256="4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
SWIPE_ENCODER_SHA256="725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf"
SWIPE_DECODER_SHA256="01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85"
EMOJI_SHA256="543a5883b9dfb0521bf8440f72fe000e37ae43de1ff6c70432ae9e02b646188f"
LEXICON_SHA256="9dac9defeb43453fb98a14b5ffa1e54aa112a4b3c6a1a5959090f79cc3d96e26"
BIGRAM_SHA256="7e07306e153a0e4a1429e6fc8d0aa3c1be6f0bf20b78459d2c69deb80d8c37f3"
TRIGRAM_SHA256="dd85903183a89ddd957978636e5366e1e11e2aed14ef5d8c9b13abea48dcb3e1"
NOTICES_SHA256="024ccd7102435af2e555af561603e1a3174361cea6d9ab53ba35cc1df02865c6"
SWIPE_LICENSE_SHA256="325a6991d53c8d9a1c70256234ba7a3f672783c65057bc2903fe29e54f212c4b"
WHISPER_COMMIT="592feef04a1802b18cbeffd0fd0eb5d02570c2ec"
EXPECTED_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
EXECUTORCH_ABIS=(arm64-v8a x86_64)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

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
DEXDUMP="$(find_build_tool dexdump)"
ZIPALIGN="$(find_build_tool zipalign)"
[[ -x "$AAPT" ]] || { echo "aapt was not found." >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "apksigner was not found." >&2; exit 1; }
[[ -x "$DEXDUMP" ]] || { echo "dexdump was not found." >&2; exit 1; }
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

# libexecutorch.so is prebuilt JNI and performs exact name-based lookups. Debug instrumentation is
# not minified, so inspect the actual release DEX and fail if R8 removed any native-boundary class.
mapfile -t dex_paths < <(unzip -Z1 "$APK" | grep -E '^classes([0-9]+)?[.]dex$' | LC_ALL=C sort)
[[ "${#dex_paths[@]}" -gt 0 ]] || { echo "Release APK contains no DEX files." >&2; exit 1; }
dex_dump="$TEMP_DIR/release-dex.txt"
: > "$dex_dump"
for index in "${!dex_paths[@]}"; do
    unzip -p "$APK" "${dex_paths[$index]}" > "$TEMP_DIR/classes-$index.dex"
    "$DEXDUMP" -d "$TEMP_DIR/classes-$index.dex" >> "$dex_dump"
done
EXECUTORCH_JNI_CLASSES=(
    org/pytorch/executorch/EValue
    org/pytorch/executorch/ExecuTorchRuntime
    org/pytorch/executorch/ExecutorchRuntimeException
    org/pytorch/executorch/Module
    org/pytorch/executorch/Tensor
    org/pytorch/executorch/extension/asr/AsrCallback
    org/pytorch/executorch/extension/asr/AsrModule
    org/pytorch/executorch/extension/llm/LlmCallback
    org/pytorch/executorch/extension/llm/LlmModule
    org/pytorch/executorch/training/SGD
    org/pytorch/executorch/training/TrainingModule
)
for class_name in "${EXECUTORCH_JNI_CLASSES[@]}"; do
    grep -Fq "Class descriptor  : 'L${class_name};'" "$dex_dump" || {
        echo "Release minification removed ExecuTorch JNI class $class_name." >&2
        exit 1
    }
done
# Dollar signs below are literal nested-class separators, not variable references.
# shellcheck disable=SC2016
FBJNI_JNI_CLASSES=(
    'com/facebook/jni/CppException'
    'com/facebook/jni/CppSystemErrorException'
    'com/facebook/jni/DestructorThread$Destructor'
    'com/facebook/jni/DestructorThread$DestructorList'
    'com/facebook/jni/DestructorThread$DestructorStack'
    'com/facebook/jni/DestructorThread$Terminus'
    'com/facebook/jni/DestructorThread'
    'com/facebook/jni/ExceptionHelper'
    'com/facebook/jni/HybridClassBase'
    'com/facebook/jni/HybridData$Destructor'
    'com/facebook/jni/HybridData'
    'com/facebook/jni/IteratorHelper'
    'com/facebook/jni/MapIteratorHelper'
    'com/facebook/jni/NativeRunnable'
    'com/facebook/jni/ThreadScopeSupport'
    'com/facebook/jni/UnknownCppException'
)
for class_name in "${FBJNI_JNI_CLASSES[@]}"; do
    grep -Fq "Class descriptor  : 'L${class_name};'" "$dex_dump" || {
        echo "Release minification removed fbjni bridge class $class_name." >&2
        exit 1
    }
done
manifest_tree="$($AAPT dump xmltree "$APK" AndroidManifest.xml)"
if grep -q 'E: instrumentation' <<< "$manifest_tree"; then
    echo "Release APK contains a test instrumentation component." >&2
    exit 1
fi
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

verify_asset_hash() {
    local path="$1"
    local expected_sha="$2"
    local actual_sha
    actual_sha="$(unzip -p "$APK" "$path" | sha256sum | awk '{print $1}')"
    [[ "$actual_sha" == "$expected_sha" ]] || {
        echo "Wrong SHA-256 for $path: expected $expected_sha, got $actual_sha" >&2
        exit 1
    }
}

verify_asset_hash "$NOTICES_PATH" "$NOTICES_SHA256"
verify_asset_hash "$SWIPE_LICENSE_PATH" "$SWIPE_LICENSE_SHA256"

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
verify_stored_asset "$EMOJI_PATH" "$EMOJI_SHA256"
verify_stored_asset "$LEXICON_PATH" "$LEXICON_SHA256"
verify_stored_asset "$BIGRAM_PATH" "$BIGRAM_SHA256"
verify_stored_asset "$TRIGRAM_PATH" "$TRIGRAM_SHA256"

# A reviewed release carries exactly these model/data payloads. Hashing the expected files alone
# is insufficient: an accidentally bundled experimental model would otherwise be signed and
# attested without appearing in the release contract or SBOM.
# Read indirectly through verify_exact_manifest's nameref.
# shellcheck disable=SC2034
mapfile -t runtime_data_paths < <(
    unzip -Z1 "$APK" \
        | awk '/^assets\/.*[.](bin|pte|onnx|tflite|lite|pt|pth|gguf|model)$/' \
        | LC_ALL=C sort
)
verify_exact_manifest "runtime model/data" EXPECTED_RUNTIME_DATA_PATHS runtime_data_paths

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

# Every packaged native object must at least be a real ELF image, and the ASR bridge must carry the
# tracked whisper.cpp snapshot identity rather than ambient Slide Git state or "unknown".
mapfile -t native_paths < <(unzip -Z1 "$APK" | sed -n '/^lib\/[^/][^/]*\/[^/][^/]*\.so$/p' | sort)
verify_exact_manifest "native library" EXPECTED_NATIVE_PATHS native_paths
for index in "${!native_paths[@]}"; do
    path="${native_paths[$index]}"
    abi="${path#lib/}"
    abi="${abi%%/*}"
    extracted="$TEMP_DIR/native-$index.so"
    unzip -p "$APK" "$path" > "$extracted"
    verify_elf_abi "$extracted" "$abi" || {
        echo "$path does not contain code for its declared ABI." >&2
        exit 1
    }
    if [[ "$path" == */libslide_asr.so ]]; then
        strings_file="$TEMP_DIR/native-$index.strings"
        strings "$extracted" > "$strings_file"
        if ! grep -Fq "$WHISPER_COMMIT" "$strings_file"; then
            echo "$path does not identify the tracked whisper.cpp commit." >&2
            exit 1
        fi
    fi
done

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

printf 'Verified %s (%s %s, all runtime assets and native ELF provenance verified, ABIs %s, signer %s)\n' \
    "$APK" "$EXPECTED_VERSION" "$EXPECTED_VERSION_CODE" "${EXPECTED_ABIS[*]}" \
    "$EXPECTED_SIGNER"
