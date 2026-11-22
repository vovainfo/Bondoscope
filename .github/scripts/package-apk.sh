#!/usr/bin/env bash
set -euo pipefail

: "${APK_KEYSTORE_BASE64:?Missing APK signing keystore}"
: "${APK_KEYSTORE_PASSWORD:?Missing APK signing password}"
: "${RELEASE_VERSION:?Missing release version}"

expected_certificate='bad7fb23c9e18b96168f921b3d61c5ba7c00db659876fc9415266c74d5257e89'
tools="$ANDROID_HOME/build-tools/36.1.0"
output="$RUNNER_TEMP/bondoscope-release"
apk="$output/Bondoscope-$RELEASE_VERSION.apk"
keystore=$(mktemp "$RUNNER_TEMP/bondoscope-keystore.XXXXXX")
aligned=$(mktemp "$RUNNER_TEMP/bondoscope-aligned.XXXXXX.apk")
trap 'rm -f -- "$keystore" "$aligned"' EXIT

printf '%s' "$APK_KEYSTORE_BASE64" | base64 --decode > "$keystore"
unset APK_KEYSTORE_BASE64
mkdir -p "$output"
"$tools/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$aligned"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass env:APK_KEYSTORE_PASSWORD --key-pass env:APK_KEYSTORE_PASSWORD \
    --v4-signing-enabled false --out "$apk" "$aligned"
unset APK_KEYSTORE_PASSWORD

"$tools/zipalign" -c -P 16 4 "$apk"
verification=$("$tools/apksigner" verify --print-certs "$apk")
certificate=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$verification")
if [[ "$certificate" != "$expected_certificate" ]]; then
    echo 'APK signing certificate differs from the published Android app' >&2
    exit 1
fi

version_code=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)$/\1/p' app/build.gradle.kts)
metadata=$("$tools/aapt" dump badging "$apk")
if [[ "$metadata" != "package: name='ru.bondmobile' versionCode='$version_code' versionName='$RELEASE_VERSION'"* ]]; then
    echo 'APK package or version differs from the Gradle configuration' >&2
    exit 1
fi

tag="v$RELEASE_VERSION"
digest="sha256:$(sha256sum "$apk" | cut -d ' ' -f 1)"
size=$(stat -c '%s' "$apk")
name=$(basename "$apk")
url="https://github.com/$GITHUB_REPOSITORY/releases/download/$tag/$name"
jq -n --arg tag "$tag" --arg name "$name" --arg digest "$digest" --argjson size "$size" --arg url "$url" '
    {
      tag_name: $tag,
      draft: false,
      prerelease: false,
      assets: [{name: $name, state: "uploaded", size: $size, digest: $digest, browser_download_url: $url}]
    }
' > "$output/bondoscope-update.json"
echo "Verified signed APK $name ($digest)"
