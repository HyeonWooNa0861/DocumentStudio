# Document Studio for macOS

HWP·HWPX 변환, DOCX 가져오기, 문단·표 셀 편집과 PDF 출력을 제공하는 로컬 앱입니다. 문서를 외부 서버로 전송하지 않습니다. 한컴과 완전히 같은 조판 결과를 보증하지 않으며 최종 제출 문서는 한컴에서 확인하세요.

## 지원 환경

- Apple Silicon Mac, macOS 13 이상. Intel Mac과 Windows는 이 릴리스에서 지원하지 않습니다.
- Java 실행 환경이 앱에 포함됩니다. 일반 설치·사용에는 Java·Xcode·Homebrew가 필요하지 않습니다.
- 최초 설치와 업데이트 시에만 GitHub 다운로드를 위한 인터넷 연결이 필요합니다.
- 현재 빌드는 ad-hoc 서명이며 Developer ID 서명과 Apple 공증을 받지 않았습니다. 다른 Mac의 Gatekeeper 실행 허용은 아직 검증하지 않았습니다. 설치 스크립트는 보안 설정이나 quarantine 속성을 변경하지 않습니다. [Apple의 앱 실행 보안 안내](https://support.apple.com/en-us/102445)를 따르세요.

## 터미널 설치

다음 명령으로 공개 GitHub 릴리스의 설치 스크립트를 내려받습니다.

```bash
REPO="HyeonWooNa0861/DocumentStudio"
INSTALL_DIR="$(mktemp -d)"
curl --fail --location "https://github.com/$REPO/releases/latest/download/install-macos.sh" -o "$INSTALL_DIR/install-macos.sh"
# 다운로드한 스크립트를 확인한 뒤 실행합니다.
bash "$INSTALL_DIR/install-macos.sh" --repo "$REPO"
```

기본 설치 위치는 `~/Applications/Document Studio.app`, 명령 실행기는 `~/.local/bin/document-studio`입니다. 관리자 권한을 사용하지 않습니다. 다른 위치에는 `--app-dir /absolute/path`와 `--bin-dir /absolute/path`를 지정합니다. 비공개 저장소는 위의 익명 다운로드를 지원하지 않으며, 접근 권한이 있는 계정으로 자산을 받은 후 아래 로컬 ZIP 설치 방식을 사용해야 합니다.

```bash
"$HOME/.local/bin/document-studio" open
"$HOME/.local/bin/document-studio" version
```

`~/.local/bin`이 PATH에 있으면 `document-studio open`처럼 실행할 수 있습니다. 설치 스크립트가 shell 설정을 자동 변경하지는 않습니다.

## 업데이트

1.6.1부터 앱 실행 시 마지막 조회 시도로부터 24시간이 지났으면 새 정식 릴리스를 확인합니다. 앱 실행 중에는 1시간마다 경과 시간을 점검합니다. 실패한 시도도 조회 시각에 포함하며 앱 재실행 후에도 유지합니다. Document Studio 메뉴에서 `업데이트 확인…`을 누르면 즉시 다시 확인하고, `새 버전 자동 확인`을 끄면 자동 조회를 중단합니다.

새 버전은 별도 안내 창에 표시되며 `변경 내용 및 다운로드` 버튼으로 공식 릴리스 페이지를 엽니다. 자동 조회 오류는 문서 작업을 방해하지 않습니다. 문서 내용은 전송하지 않으며, 버전 확인을 위해 GitHub에 접속합니다. 기존 1.6.0 사용자는 이 기능을 포함한 버전을 한 번 직접 설치해야 합니다.

```bash
"$HOME/.local/bin/document-studio" update
```

수정한 문서를 저장하고 앱을 정상 종료한 뒤 실행하세요. 명령은 최신 정식 릴리스의 태그를 한 번 확정하고 같은 태그의 ZIP·SHA256SUMS를 내려받습니다. 체크섬·앱 식별자·버전·코드 서명 무결성을 확인한 뒤 설치 앱을 교체합니다. 동일 설치 위치와 앱 식별자 `local.nahw.hwpxstudio`를 유지합니다. 문서 파일과 UserDefaults 설정을 삭제하지 않습니다. 기존 앱은 설치 폴더의 `.document-studio-backup.*`에 복구용으로 보관합니다.

GitHub에 소스만 push해도 기존 사용자 앱이 자동 업데이트되는 것은 아닙니다. 배포자는 버전을 올리고 새 `vX.Y.Z` Release와 설치 자산을 게시해야 합니다. 사용자는 위 명령을 실행합니다. 앱 내부의 자동 업데이트 기능은 포함하지 않습니다.

특정 버전 또는 접근 권한을 통해 받은 ZIP을 설치할 수도 있습니다.

```bash
bash install-macos.sh --repo HyeonWooNa0861/DocumentStudio --version 1.6.0
bash install-macos.sh --repo HyeonWooNa0861/DocumentStudio --version 1.6.0 --archive ./Document-Studio-macos-arm64.zip --sha256 VERIFIED_SHA256
```

체크섬은 신뢰할 수 있는 해당 릴리스의 SHA256SUMS와 대조하세요. 체크섬은 같은 배포 경로의 파일 무결성 확인이며 Apple 공증을 대체하지 않습니다.

## 소스 빌드와 릴리스 준비

소스 빌드에는 Apple Silicon Mac의 Xcode 또는 Command Line Tools가 필요합니다. 빌드 입력은 고정된 버전과 SHA-256으로 복원합니다.

```bash
bash scripts/setup-engine.sh
bash scripts/build-engine.sh
bash scripts/build-app.sh
bash scripts/package-release.sh
```

설치·업데이트 회귀 검사는 `/usr/bin/python3 tests/test_release_installer.py`로 실행합니다. 임시 서명 앱과 제어된 프로세스 목록을 사용하며, 실제 프로세스 조회 검사는 별도입니다. 호스트가 `/bin/ps` 실행을 금지하면 해당 검사만 건너뜁니다. 다른 Mac의 Gatekeeper 실행 검증을 대신하지 않습니다.

`package-release.sh`는 현재 검증된 `dist/Document Studio.app`의 버전과 리소스를 확인하여 `.release/vX.Y.Z/`에 다음을 준비합니다. 실행 중인 원래 앱은 수정하지 않습니다. 공개 전에는 먼저 변경에 맞는 회귀 검사를 완료하고 현재 소스의 앱을 재빌드하세요.

- `Document-Studio-macos-arm64.zip`: 앱·Java 런타임·엔진·라이선스·업데이트 스크립트
- `install-macos.sh`, `SHA256SUMS`
- `Document-Studio-source-X.Y.Z.tar.gz`: 명시적으로 허용한 앱·엔진 소스와 빌드 도구
- `OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz`: 포함한 Java 런타임의 대응 upstream 소스. 공식 SHA-256을 검사하며 일반 설치 시에는 내려받지 않습니다.
- `source/`: GitHub 저장소용으로 검토할 소스 트리

실제 문서·화면 캡처·검증 산출물·로컬 캐시·전체 JDK·다운로드 기록을 Git 소스에 포함하지 않습니다. 별도의 자체 앱 소스 라이선스는 아직 지정하지 않았습니다. 라이브러리와 Java 런타임에는 각각의 라이선스가 적용됩니다. `THIRD_PARTY_NOTICES.md`와 `engine/DEPENDENCIES.md`를 확인하세요.

배포 시 저장소·브랜치를 확정하고 검토한 소스 커밋에 `vX.Y.Z` 태그를 지정합니다. GitHub Release에는 ZIP·설치 스크립트·SHA256SUMS·앱 소스 아카이브·Java 대응 소스 아카이브를 함께 게시합니다. 정식 Developer ID·공증을 도입할 경우 서명된 번들을 다시 ad-hoc 서명하지 않는 별도 릴리스 경로로 전환해야 합니다.
