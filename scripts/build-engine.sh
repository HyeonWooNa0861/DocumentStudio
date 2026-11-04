#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$PROJECT_DIR/engine"
RUNTIME_DIR="$PROJECT_DIR/runtime"
JDK_HOME="$RUNTIME_DIR/jdk/Contents/Home"
BUILD_DIR="$ENGINE_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
APP_RUNTIME="$RUNTIME_DIR/macos-arm64"

if [[ ! -x "$JDK_HOME/bin/javac" ]]; then
  echo "Retained JDK is missing. Run scripts/setup-engine.sh first." >&2
  exit 1
fi

"$PROJECT_DIR/scripts/setup-engine.sh" >/dev/null

rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR"

sources=()
while IFS= read -r -d '' source; do
  sources+=("$source")
done < <(find "$ENGINE_DIR/vendor/src/main/java" "$ENGINE_DIR/src/main/java" -name '*.java' -type f -print0)

if [[ ${#sources[@]} -eq 0 ]]; then
  echo "No Java sources found." >&2
  exit 1
fi

"$JDK_HOME/bin/javac" -encoding UTF-8 --release 8 \
  -cp "$ENGINE_DIR/lib/*" -d "$CLASSES_DIR" "${sources[@]}"

"$JDK_HOME/bin/jar" --create --file "$BUILD_DIR/engine.jar" \
  --main-class local.nahw.hwpxstudio.StudioEngine -C "$CLASSES_DIR" .

rm -rf "$APP_RUNTIME"
"$JDK_HOME/bin/jlink" \
  --add-modules java.base,java.xml \
  --strip-debug --no-header-files --no-man-pages --compress=2 \
  --output "$APP_RUNTIME"

"$APP_RUNTIME/bin/java" -cp "$BUILD_DIR/engine.jar:$ENGINE_DIR/lib/*" \
  local.nahw.hwpxstudio.StudioEngine version
