#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$PROJECT_DIR/engine"
RUNTIME_DIR="$PROJECT_DIR/runtime"
DOWNLOAD_DIR="$RUNTIME_DIR/downloads"

HWP2_COMMIT="edc05278506b663d5bdd98050a51f54b7ff5e0bc"
HWP2_ARCHIVE="$DOWNLOAD_DIR/hwp2hwpx-$HWP2_COMMIT.tar.gz"
HWP2_URL="https://github.com/neolord0/hwp2hwpx/archive/$HWP2_COMMIT.tar.gz"
HWPLIB="$ENGINE_DIR/lib/hwplib-1.1.10.jar"
HWPLIB_URL="https://repo.maven.apache.org/maven2/kr/dogfoot/hwplib/1.1.10/hwplib-1.1.10.jar"
HWPXLIB="$ENGINE_DIR/lib/hwpxlib-1.0.9.jar"
HWPXLIB_URL="https://repo.maven.apache.org/maven2/kr/dogfoot/hwpxlib/1.0.9/hwpxlib-1.0.9.jar"
JDK_ARCHIVE="$DOWNLOAD_DIR/temurin17-macos-aarch64.tar.gz"
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz"
JDK_CHECKSUM_FILE="$JDK_ARCHIVE.sha256.txt"
JDK_CHECKSUM_URL="$JDK_URL.sha256.txt"

mkdir -p "$ENGINE_DIR/lib" "$ENGINE_DIR/vendor" "$ENGINE_DIR/licenses" \
  "$RUNTIME_DIR/jdk" "$DOWNLOAD_DIR"

download_if_missing() {
  local destination="$1"
  local url="$2"
  if [[ -f "$destination" ]]; then
    return
  fi
  local partial="$destination.part"
  trap 'rm -f "$partial"' RETURN
  curl -L --fail --retry 3 -o "$partial" "$url"
  mv "$partial" "$destination"
  trap - RETURN
}

verify_hash() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch: $file" >&2
    exit 1
  fi
}

download_if_missing "$HWP2_ARCHIVE" "$HWP2_URL"
download_if_missing "$HWPLIB" "$HWPLIB_URL"
download_if_missing "$HWPXLIB" "$HWPXLIB_URL"
download_if_missing "$JDK_ARCHIVE" "$JDK_URL"
download_if_missing "$JDK_CHECKSUM_FILE" "$JDK_CHECKSUM_URL"

verify_hash "$HWP2_ARCHIVE" "119521de323873b8286d06063d7fd2359a42cfa5f4b4d85c56e43ab73f42f23d"
verify_hash "$HWPLIB" "fce557a7b4aff0c626c544558e0939459601a008d9378457b6183f7f3ae9d1b9"
verify_hash "$HWPXLIB" "c2461bde566d200770100b8572ae5e944e9d63bb92642d2840affb7cd88b8566"
verify_hash "$JDK_ARCHIVE" "196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8"
if [[ "$(awk '{print $1}' "$JDK_CHECKSUM_FILE")" != "196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8" ]]; then
  echo "Official Temurin checksum response does not match the pinned archive." >&2
  exit 1
fi

if [[ ! -f "$ENGINE_DIR/vendor/src/main/java/kr/dogfoot/hwp2hwpx/Hwp2Hwpx.java" ]]; then
  if [[ -n "$(find "$ENGINE_DIR/vendor" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "vendor directory is non-empty but incomplete; refusing to replace it." >&2
    exit 1
  fi
  tar -xzf "$HWP2_ARCHIVE" --strip-components=1 -C "$ENGINE_DIR/vendor"
fi

if [[ ! -x "$RUNTIME_DIR/jdk/Contents/Home/bin/javac" ]]; then
  if [[ -n "$(find "$RUNTIME_DIR/jdk" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "runtime/jdk is non-empty but incomplete; refusing to replace it." >&2
    exit 1
  fi
  tar -xzf "$JDK_ARCHIVE" --strip-components=1 -C "$RUNTIME_DIR/jdk"
fi

cp "$ENGINE_DIR/vendor/license.txt" "$ENGINE_DIR/licenses/hwp2hwpx-Apache-2.0.txt"
cp "$ENGINE_DIR/vendor/license.txt" "$ENGINE_DIR/licenses/hwplib-Apache-2.0.txt"
cp "$ENGINE_DIR/vendor/license.txt" "$ENGINE_DIR/licenses/hwpxlib-Apache-2.0.txt"

echo "Engine inputs verified."
"$RUNTIME_DIR/jdk/Contents/Home/bin/java" -version
