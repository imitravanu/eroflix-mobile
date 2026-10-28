#!/bin/sh
set -eu
EROFLIX_MOBILE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$EROFLIX_MOBILE_ROOT"
./gradlew assembleDebug lintDebug verifyUnitTests :app:assembleDebugAndroidTest --console=plain
exec python3 tools/dev/inspect-apk.py
