#!/bin/sh
# Build an isolated neutral APK to validate AGP, numeric API 37 and packaging.
set -eu
EROFLIX_MOBILE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
EROFLIX_SMOKE="$EROFLIX_MOBILE_ROOT/local-data/toolchain-smoke"
mkdir -p "$EROFLIX_SMOKE/src/main/java/tech/imitravanu/eroflix/toolchainsmoke"
cat > "$EROFLIX_SMOKE/settings.gradle.kts" <<'EOF'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "eroflix-toolchain-smoke"
EOF
cat > "$EROFLIX_SMOKE/build.gradle.kts" <<'EOF'
plugins { id("com.android.application") version "9.4.0" }
android {
    namespace = "tech.imitravanu.eroflix.toolchainsmoke"
    compileSdk = 37
    defaultConfig {
        applicationId = "tech.imitravanu.eroflix.toolchainsmoke"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }
}
EOF
cat > "$EROFLIX_SMOKE/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.parallel=false
kotlin.compiler.execution.strategy=in-process
EOF
cat > "$EROFLIX_SMOKE/src/main/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Toolchain smoke" android:allowBackup="false" />
</manifest>
EOF
cat > "$EROFLIX_SMOKE/src/main/java/tech/imitravanu/eroflix/toolchainsmoke/ToolchainCheck.java" <<'EOF'
package tech.imitravanu.eroflix.toolchainsmoke;
public final class ToolchainCheck {
    private ToolchainCheck() {}
    public static int runtimeApi() { return android.os.Build.VERSION.SDK_INT; }
}
EOF
exec "$EROFLIX_MOBILE_ROOT/tools/dev/with-toolchain.sh" gradle \
    -p "$EROFLIX_SMOKE" --no-daemon --console=plain assembleDebug
