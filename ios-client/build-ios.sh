#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v xcodegen >/dev/null || { echo "Install XcodeGen: brew install xcodegen"; exit 1; }
xcodegen generate --spec project.yml
xcodebuild -project VisionVPN.xcodeproj -scheme VisionVPN -sdk iphoneos -configuration Debug CODE_SIGNING_ALLOWED=NO build
