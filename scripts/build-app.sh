#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
project_root="$PWD"
mkdir -p .build/module-cache-v2 dist
if [[ ! -f engine/build/engine.jar || ! -x runtime/macos-arm64/bin/java ]]; then
  echo '먼저 bash scripts/setup-engine.sh 및 bash scripts/build-engine.sh를 실행하세요.' >&2
  exit 2
fi
if [[ "$(uname -m)" != arm64 ]]; then
  echo '이 빌드는 Apple Silicon Mac용입니다.' >&2
  exit 2
fi
xcrun swiftc -swift-version 5 -target arm64-apple-macosx13.0 -O \
  -module-cache-path "$project_root/.build/module-cache-v2" Sources/*.swift \
  -framework AppKit -framework WebKit -framework PDFKit \
  -o .build/DocumentStudio
bundle="$project_root/dist/Document Studio.app"
staging_root="$(mktemp -d "$project_root/.build/app-bundle-XXXXXX")"
staged_bundle="$staging_root/Document Studio.app"
mkdir -p "$staged_bundle/Contents/MacOS" "$staged_bundle/Contents/Resources/Engine/lib" "$staged_bundle/Contents/Resources/Runtime"
cp .build/DocumentStudio "$staged_bundle/Contents/MacOS/DocumentStudio"
cp Resources/Info.plist "$staged_bundle/Contents/Info.plist"
cp scripts/install-macos.sh "$staged_bundle/Contents/Resources/install-macos.sh"
cp engine/build/engine.jar "$staged_bundle/Contents/Resources/Engine/engine.jar"
cp engine/lib/*.jar "$staged_bundle/Contents/Resources/Engine/lib/"
ditto runtime/macos-arm64 "$staged_bundle/Contents/Resources/Runtime"
if [[ ! -d Resources/VisualEditor ]]; then
  echo 'Resources/VisualEditor 리소스를 찾을 수 없습니다.' >&2
  exit 2
fi
ditto Resources/VisualEditor "$staged_bundle/Contents/Resources/VisualEditor"
if [[ -d engine/licenses ]]; then
  ditto engine/licenses "$staged_bundle/Contents/Resources/Licenses"
fi
if [[ -f THIRD_PARTY_NOTICES.md ]]; then
  cp THIRD_PARTY_NOTICES.md "$staged_bundle/Contents/Resources/THIRD_PARTY_NOTICES.md"
fi
if [[ -f Resources/AppIcon.icns ]]; then
  cp Resources/AppIcon.icns "$staged_bundle/Contents/Resources/AppIcon.icns"
fi
/usr/bin/plutil -lint "$staged_bundle/Contents/Info.plist"
/usr/bin/codesign --force --deep --sign - "$staged_bundle"
/usr/bin/codesign --verify --deep --strict "$staged_bundle"
# Retain a previous build for recovery; never overlay wildcard-loaded libraries.
if [[ -d "$bundle" ]]; then
  mv "$bundle" "$staging_root/Previous Document Studio.app"
fi
if ! mv "$staged_bundle" "$bundle"; then
  if [[ -d "$staging_root/Previous Document Studio.app" ]]; then
    mv "$staging_root/Previous Document Studio.app" "$bundle"
  fi
  exit 1
fi
rmdir "$staging_root" 2>/dev/null || true
echo "앱 빌드 완료: $bundle"
