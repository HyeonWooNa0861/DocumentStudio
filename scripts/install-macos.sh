#!/bin/bash
# Install a verified macOS release without changing Gatekeeper settings.
set -euo pipefail
repo=""; version=""; archive=""; checksum=""
app_dir="$HOME/Applications"; bin_dir="$HOME/.local/bin"
action=install
fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
  echo 'Usage: install-macos.sh [install|update|version|open] --repo OWNER/REPO [--version X.Y.Z] [--app-dir PATH] [--bin-dir PATH]'
  echo 'Offline: add --archive FILE --sha256 HEX --version X.Y.Z'
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    install|update|version|open) action="$1"; shift ;;
    --repo|--version|--app-dir|--bin-dir|--archive|--sha256)
      [[ $# -ge 2 && -n "$2" ]] || fail "Missing value for $1"
      case "$1" in
        --repo) repo="$2" ;; --version) version="${2#v}" ;;
        --app-dir) app_dir="$2" ;; --bin-dir) bin_dir="$2" ;;
        --archive) archive="$2" ;; --sha256) checksum="$2" ;;
      esac
      shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail "Unknown argument: $1" ;;
  esac
done
[[ "$(/usr/bin/uname -s)" == Darwin && "$(/usr/bin/uname -m)" == arm64 ]] || fail 'Apple Silicon Mac만 지원합니다.'
os_major="$(/usr/bin/sw_vers -productVersion | cut -d. -f1)"
[[ "$os_major" -ge 13 ]] || fail 'macOS 13 이상이 필요합니다.'
[[ "$app_dir" == /* && "$bin_dir" == /* ]] || fail '설치 경로는 절대 경로여야 합니다.'
[[ "$app_dir$bin_dir" != *$'\n'* ]] || fail '설치 경로에 줄바꿈을 사용할 수 없습니다.'
app="$app_dir/Document Studio.app"
launcher="$bin_dir/document-studio"
if [[ "$action" == version ]]; then
  /usr/bin/plutil -extract CFBundleShortVersionString raw -o - "$app/Contents/Info.plist"
  exit
fi
if [[ "$action" == open ]]; then /usr/bin/open "$app"; exit; fi
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail '--repo OWNER/REPO를 지정하세요.'
[[ -z "$version" || "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail '버전은 X.Y.Z 형식이어야 합니다.'
mkdir -p "$app_dir" "$bin_dir"
lock="$app_dir/.document-studio-install.lock"
mkdir "$lock" 2>/dev/null || fail '다른 설치 작업이 진행 중입니다. 기존 설치 잠금을 임의로 삭제하지 마세요.'
stage=""; launcher_tmp=""
cleanup() {
  [[ -z "$stage" ]] || rm -rf "$stage"
  [[ -z "$launcher_tmp" ]] || rm -f "$launcher_tmp"
  rmdir "$lock"
}
trap cleanup EXIT
[[ ! -L "$app" && ! -L "$launcher" ]] || fail '기존 앱 또는 실행기가 심볼릭 링크이면 교체하지 않습니다.'
if [[ -e "$launcher" ]] && ! /usr/bin/grep -q '^# Document Studio managed launcher$' "$launcher"; then
  fail "다른 실행기를 덮어쓰지 않습니다: $launcher"
fi
if [[ -e "$app" ]]; then
  installed_id="$(/usr/bin/plutil -extract CFBundleIdentifier raw -o - "$app/Contents/Info.plist")"
  [[ "$installed_id" == local.nahw.hwpxstudio ]] || fail '같은 이름의 다른 앱을 교체하지 않습니다.'
fi
app_running() {
  local processes
  processes="$(/bin/ps -axo comm=)" || fail '실행 중인 앱을 확인하지 못했습니다.'
  printf '%s\n' "$processes" | /usr/bin/grep -F -x "$app/Contents/MacOS/DocumentStudio" >/dev/null
}
if app_running; then fail '저장하지 않은 변경을 보존하고 Document Studio를 종료한 뒤 다시 실행하세요.'; fi
stage="$(mktemp -d "$app_dir/.document-studio-stage.XXXXXX")"
launcher_tmp="$(mktemp "$bin_dir/.document-studio-launcher.XXXXXX")"
if [[ -n "$archive" ]]; then
  [[ -n "$version" && "$checksum" =~ ^[0-9a-f]{64}$ ]] || fail '로컬 ZIP은 --version과 --sha256이 필요합니다.'
  cp "$archive" "$stage/app.zip"
else
  [[ -z "$checksum" ]] || fail '--sha256은 --archive와 함께 사용하세요.'
  if [[ -z "$version" ]]; then
    /usr/bin/curl --fail --location --retry 3 --proto '=https' \
      "https://api.github.com/repos/$repo/releases/latest" -o "$stage/release.json"
    tag="$(/usr/bin/plutil -extract tag_name raw -o - "$stage/release.json")"
    version="${tag#v}"
  fi
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail '유효한 공개 릴리스를 찾지 못했습니다.'
  # Resolve the release once; never mix assets from two latest releases.
  base="https://github.com/$repo/releases/download/v$version"
  /usr/bin/curl --fail --location --retry 3 --proto '=https' "$base/Document-Studio-macos-arm64.zip" -o "$stage/app.zip"
  /usr/bin/curl --fail --location --retry 3 --proto '=https' "$base/SHA256SUMS" -o "$stage/SHA256SUMS"
  checksum="$(awk '$2 == "Document-Studio-macos-arm64.zip" {print $1}' "$stage/SHA256SUMS")"
  [[ "$checksum" =~ ^[0-9a-f]{64}$ ]] || fail '릴리스 체크섬이 유효하지 않습니다.'
fi
actual="$(/usr/bin/openssl dgst -sha256 "$stage/app.zip" | awk '{print $NF}')"
[[ "$actual" == "$checksum" ]] || fail 'SHA-256 불일치: 기존 앱을 유지합니다.'
/usr/bin/unzip -Z1 "$stage/app.zip" > "$stage/entries"
awk 'index($0,"Document Studio.app/") != 1 || $0 ~ /(^|\/)\.\.(\/|$)/ {bad=1} END {exit bad}' "$stage/entries" || fail '허용하지 않는 ZIP 경로입니다.'
mkdir "$stage/unpacked"
/usr/bin/ditto -x -k "$stage/app.zip" "$stage/unpacked"
incoming="$stage/unpacked/Document Studio.app"
[[ -d "$incoming" && ! -L "$incoming" ]] || fail '앱 번들이 없습니다.'
id="$(/usr/bin/plutil -extract CFBundleIdentifier raw -o - "$incoming/Contents/Info.plist")"
app_version="$(/usr/bin/plutil -extract CFBundleShortVersionString raw -o - "$incoming/Contents/Info.plist")"
[[ "$id" == local.nahw.hwpxstudio && "$app_version" == "$version" ]] || fail '앱 식별자 또는 버전이 릴리스와 다릅니다.'
[[ -x "$incoming/Contents/MacOS/DocumentStudio" && -f "$incoming/Contents/Resources/install-macos.sh" ]] || fail '앱 실행 파일 또는 업데이트 스크립트가 없습니다.'
[[ -x "$incoming/Contents/Resources/Runtime/bin/java" && -f "$incoming/Contents/Resources/Engine/engine.jar" ]] || fail 'Java 런타임 또는 변환 엔진이 없습니다.'
/usr/bin/codesign --verify --deep --strict "$incoming"
# A valid ad-hoc signature checks integrity; it is not Apple notarization.
printf '#!/bin/bash\n# Document Studio managed launcher\nexec /bin/bash %q --repo %q --app-dir %q --bin-dir %q "$@"\n' \
  "$app/Contents/Resources/install-macos.sh" "$repo" "$app_dir" "$bin_dir" > "$launcher_tmp"
chmod 755 "$launcher_tmp"
if app_running; then fail '설치 직전에 앱이 실행되었습니다. 앱을 정상 종료하고 다시 시도하세요.'; fi
backup=""
if [[ -d "$app" ]]; then
  backup="$(mktemp -d "$app_dir/.document-studio-backup.XXXXXX")"
  mv "$app" "$backup/Document Studio.app"
fi
[[ ! -e "$app" ]] || fail '설치 대상이 변경되어 앱 교체를 중단했습니다.'
if ! mv "$incoming" "$app"; then
  [[ -z "$backup" ]] || mv "$backup/Document Studio.app" "$app"
  fail '앱 교체 실패: 이전 앱을 복구했습니다.'
fi
if ! mv -f "$launcher_tmp" "$launcher"; then
  mv "$app" "$stage/failed-new.app"
  [[ -z "$backup" ]] || mv "$backup/Document Studio.app" "$app"
  fail '실행기 설치 실패: 이전 앱을 복구했습니다.'
fi
printf 'Document Studio %s 설치 완료: %s\n' "$version" "$app"
printf '업데이트: %q update\n실행: %q open\n' "$launcher" "$launcher"
[[ -z "$backup" ]] || printf '이전 앱 보관: %s\n' "$backup/Document Studio.app"
echo '이 빌드는 Apple 공증을 받지 않았습니다. macOS 보안 설정을 자동 변경하지 않습니다.'
