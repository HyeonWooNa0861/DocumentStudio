#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd -P)"
mkdir -p .build/update-check .build/module-cache-documentstudio
xcrun swiftc -swift-version 5 -module-cache-path "$root/.build/module-cache-documentstudio" Sources/ReleaseUpdate.swift tests/verify_updates.swift -o .build/update-check/verify-updates
.build/update-check/verify-updates
xcrun swiftc -swift-version 5 -module-cache-path "$root/.build/module-cache-documentstudio" Sources/ReleaseUpdate.swift Sources/UpdateController.swift tests/verify_update_controller.swift -framework AppKit -o .build/update-check/verify-controller
.build/update-check/verify-controller
