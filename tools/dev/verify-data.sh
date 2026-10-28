#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
python3 -m unittest discover -s tools/catalog/tests -v
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest verifyUnitTests lintDebug "$@"
python3 tools/dev/inspect-apk.py --output-dir artifacts/p02
