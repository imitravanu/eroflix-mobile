#!/bin/sh
set -eu
EROFLIX_MOBILE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
EROFLIX_TOOLS="$EROFLIX_MOBILE_ROOT/local-data/toolchain"
export JAVA_HOME="$EROFLIX_TOOLS/jdk-17"
export ANDROID_HOME="$EROFLIX_TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$EROFLIX_TOOLS/android-user"
export GRADLE_USER_HOME="$EROFLIX_TOOLS/gradle-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/15859902/bin:$EROFLIX_TOOLS/gradle-9.6.0/bin:$PATH"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo 'Run python3 tools/dev/setup.py first.' >&2
    exit 1
fi
exec "$@"
