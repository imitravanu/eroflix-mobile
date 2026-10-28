import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
extra["androidApi"] = libs.versions.android.api.get().toInt()
extra["androidBuildTools"] = libs.versions.build.tools.get()
subprojects {
    dependencyLocking { lockAllConfigurations() }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = rootProject.extra["androidApi"] as Int
            buildToolsVersion = rootProject.extra["androidBuildTools"] as String
            defaultConfig { minSdk = rootProject.extra["androidApi"] as Int }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
tasks.register("verifyUnitTests") {
    group = "verification"
    description = "Run actual JVM and Android local-unit suites."
    dependsOn(":core:model:test", ":core:testing:test", ":app:testDebugUnitTest",
        ":core:data:testDebugUnitTest", ":core:network:testDebugUnitTest",
        ":core:media:testDebugUnitTest", ":core:ui:testDebugUnitTest")
}
tasks.register<Exec>("verifyAndroidTests") {
    group = "verification"
    description = "Run real data, app and process-restart suites on the explicitly selected API 37 device."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest", ":core:data:assembleDebugAndroidTest", "verifyConnectedTarget")
    commandLine("python3", "tools/dev/run-device-tests.py")
}

val verifyConnectedTarget = tasks.register<Exec>("verifyConnectedTarget") {
    group = "verification"
    commandLine("python3", "tools/dev/require-device.py")
}
project(":app").tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(verifyConnectedTarget)
}

project(":core:data").tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(verifyConnectedTarget) }

tasks.register<Exec>("verifySourceFeasibility") {
    group = "verification"
    description = "Explicit P03 native/download/WebView controlled gate on an authorized API 37 device."
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest", "verifyConnectedTarget")
    doFirst { check(providers.gradleProperty("sourceLab").orNull == "true") { "P03 source fixtures require -PsourceLab=true." } }
    commandLine("python3", "tools/dev/run-source-tests.py")
}
