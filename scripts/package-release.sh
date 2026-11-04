#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
app="$root/dist/Document Studio.app"
version="$(/usr/bin/plutil -extract CFBundleShortVersionString raw -o - Resources/Info.plist)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid version' >&2; exit 1; }
[[ -d "$app" ]] || { echo 'Build the app first.' >&2; exit 1; }
[[ "$(/usr/bin/plutil -extract CFBundleShortVersionString raw -o - "$app/Contents/Info.plist")" == "$version" ]] || { echo 'Rebuild the current app version.' >&2; exit 1; }
for key in CFBundleIdentifier CFBundleVersion; do
  [[ "$(/usr/bin/plutil -extract "$key" raw -o - "$app/Contents/Info.plist")" == "$(/usr/bin/plutil -extract "$key" raw -o - Resources/Info.plist)" ]] || { echo "Stale or unexpected app: $key" >&2; exit 1; }
done
output="$root/.release/v$version"
mkdir -p "$root/.release"
lock="$root/.release/.package-v$version.lock"
mkdir "$lock" 2>/dev/null || { echo 'Another packaging operation is active.' >&2; exit 1; }
stage=""
cleanup() { [[ -z "$stage" ]] || rm -rf "$stage"; rmdir "$lock"; }
trap cleanup EXIT
[[ ! -e "$output" ]] || { echo "Release already exists; preserve it: $output" >&2; exit 1; }
stage="$(mktemp -d "$root/.release/staging.XXXXXX")"
mkdir "$stage/bundle" "$stage/assets" "$stage/source"
/usr/bin/ditto "$app" "$stage/bundle/Document Studio.app"
cp scripts/install-macos.sh "$stage/bundle/Document Studio.app/Contents/Resources/install-macos.sh"
cp THIRD_PARTY_NOTICES.md "$stage/bundle/Document Studio.app/Contents/Resources/THIRD_PARTY_NOTICES.md"
/usr/bin/codesign --force --deep --sign - "$stage/bundle/Document Studio.app"
/usr/bin/codesign --verify --deep --strict "$stage/bundle/Document Studio.app"
COPYFILE_DISABLE=1 /usr/bin/ditto -c -k --norsrc --keepParent "$stage/bundle/Document Studio.app" "$stage/assets/Document-Studio-macos-arm64.zip"
cp scripts/install-macos.sh "$stage/assets/install-macos.sh"
# Explicit allowlist: no actual documents, validation output, caches or runtimes.
mkdir -p "$stage/source/Sources" "$stage/source/Resources/VisualEditor" "$stage/source/scripts" "$stage/source/tests"
cp Sources/*.swift "$stage/source/Sources/"
cp Resources/Info.plist Resources/AppIcon.icns "$stage/source/Resources/"
cp Resources/VisualEditor/index.html Resources/VisualEditor/studio.css \
  Resources/VisualEditor/studio.js Resources/VisualEditor/renderer-core.js "$stage/source/Resources/VisualEditor/"
for name in setup-engine.sh build-engine.sh build-app.sh make-icon.swift install-macos.sh package-release.sh; do
  cp "scripts/$name" "$stage/source/scripts/"
done
cp tests/test_release_installer.py "$stage/source/tests/"
mkdir -p "$stage/source/engine/src/main" "$stage/source/engine/licenses"
/usr/bin/ditto engine/src/main/java "$stage/source/engine/src/main/java"
cp engine/DEPENDENCIES.md engine/CHECKSUMS.sha256 "$stage/source/engine/"
cp engine/licenses/*.txt "$stage/source/engine/licenses/"
cp DISTRIBUTION.md "$stage/source/README.md"
cp DISTRIBUTION.md "$stage/source/DISTRIBUTION.md"
cp THIRD_PARTY_NOTICES.md .gitignore "$stage/source/"
COPYFILE_DISABLE=1 /usr/bin/tar -czf "$stage/assets/Document-Studio-source-$version.tar.gz" -C "$stage/source" .
java_source_name='OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz'
java_source="$root/runtime/downloads/$java_source_name"
if [[ ! -f "$java_source" ]]; then
  /usr/bin/curl --fail --location --retry 3 --proto '=https' \
    "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/$java_source_name" -o "$stage/$java_source_name"
  mkdir -p "$root/runtime/downloads"
  [[ "$(/usr/bin/openssl dgst -sha256 "$stage/$java_source_name" | awk '{print $NF}')" == 21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214 ]] || { echo 'Java source checksum mismatch.' >&2; exit 1; }
  mv "$stage/$java_source_name" "$java_source"
fi
[[ "$(/usr/bin/openssl dgst -sha256 "$java_source" | awk '{print $NF}')" == 21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214 ]] || { echo 'Retained Java source checksum mismatch.' >&2; exit 1; }
cp "$java_source" "$stage/assets/"
(cd "$stage/assets"
  for name in Document-Studio-macos-arm64.zip "Document-Studio-source-$version.tar.gz" install-macos.sh "$java_source_name"; do
    hash="$(/usr/bin/openssl dgst -sha256 "$name" | awk '{print $NF}')"
    printf '%s  %s\n' "$hash" "$name"
  done > SHA256SUMS
)
/usr/bin/ditto "$stage/source" "$stage/assets/source"
mv "$stage/assets" "$output"
echo "Prepared release: $output"
echo 'This is an ad-hoc signed, non-notarized build. No GitHub upload was performed.'
