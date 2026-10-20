#!/usr/bin/env bash
# KMK -->
set -euo pipefail

release_tag=${1:?Usage: sign_release.sh vX.Y.Z output-directory}
output_dir=${2:?Usage: sign_release.sh vX.Y.Z output-directory}
[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected a vX.Y.Z release tag' >&2; exit 1; }
: "${KOMIKKU_RELEASE_KEYSTORE:?Set the release keystore path}"
: "${KOMIKKU_RELEASE_ALIAS:?Set the release key alias}"
: "${KOMIKKU_RELEASE_STORE_PASSWORD:?Set the keystore password}"
: "${KOMIKKU_RELEASE_KEY_PASSWORD:?Set the key password}"
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}
apksigner="$sdk_root/build-tools/36.0.0/apksigner"
mkdir -p "$output_dir"

for abi in universal arm64-v8a armeabi-v7a x86 x86_64; do
    input="app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
    output="$output_dir/Yomira-$release_tag-$abi.apk"
    "$apksigner" sign --ks "$KOMIKKU_RELEASE_KEYSTORE" --ks-key-alias "$KOMIKKU_RELEASE_ALIAS" \
        --ks-pass env:KOMIKKU_RELEASE_STORE_PASSWORD --key-pass env:KOMIKKU_RELEASE_KEY_PASSWORD \
        --out "$output" "$input"
    "$apksigner" verify --verbose "$output"
done
(
    cd "$output_dir"
    sha256sum Yomira-"$release_tag"-*.apk > SHA256SUMS.txt
    sha256sum --check SHA256SUMS.txt
)
# KMK <--
