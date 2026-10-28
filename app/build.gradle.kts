abstract class CatalogAssetsTask : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "tech.imitravanu.eroflix"
    compileSdk = rootProject.extra["androidApi"] as Int
    buildToolsVersion = rootProject.extra["androidBuildTools"] as String
    defaultConfig {
        applicationId = "tech.imitravanu.eroflix"
        minSdk = rootProject.extra["androidApi"] as Int
        targetSdk = rootProject.extra["androidApi"] as Int
        versionCode = 1
        versionName = "0.2.0-data"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val releaseSigning = listOf("EROFLIX_SIGNING_STORE_FILE", "EROFLIX_SIGNING_STORE_PASSWORD", "EROFLIX_SIGNING_KEY_ALIAS", "EROFLIX_SIGNING_KEY_PASSWORD")
        .map { System.getenv(it) }
    require(releaseSigning.all { it.isNullOrBlank() } || releaseSigning.all { !it.isNullOrBlank() }) {
        "Release signing requires all four EROFLIX_SIGNING_* environment variables."
    }
    val configuredRelease = if (releaseSigning.all { !it.isNullOrBlank() }) signingConfigs.create("ownerRelease") {
        storeFile = file(releaseSigning[0]!!)
        storePassword = releaseSigning[1]
        keyAlias = releaseSigning[2]
        keyPassword = releaseSigning[3]
    } else null
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-preview"; ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release { isMinifyEnabled = false; ndk { abiFilters += "arm64-v8a" }; if (configuredRelease != null) signingConfig = configuredRelease }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = false } }
    androidResources { noCompress += "bin" }
}
val catalogInput = providers.gradleProperty("catalogDir")
val generatedCatalog = layout.buildDirectory.dir("generated/catalogAssets")
val generatedTestCatalog = layout.buildDirectory.dir("generated/catalogTestAssets")
android {
    defaultConfig {
        buildConfigField("boolean", "CATALOG_FIXTURE", (!catalogInput.isPresent).toString())
        testInstrumentationRunnerArguments["class"] = if(catalogInput.isPresent) "tech.imitravanu.eroflix.FullCatalogDeviceTest" else "tech.imitravanu.eroflix.FoundationSmokeTest"
    }
}
val prepareCatalogAssets = tasks.register<CatalogAssetsTask>("prepareCatalogAssets") {
    inputs.files(rootProject.fileTree("tools/catalog") { exclude("**/__pycache__/**") }); inputs.file(rootProject.file("core/data/src/main/assets/schema/catalog-v1.sql"))
    inputs.property("catalogInput", catalogInput.orElse("fixture"))
    if(catalogInput.isPresent) inputs.files(listOf("catalog.db", "catalog.db.gz", "manifest.json").map { rootProject.file(catalogInput.get()).resolve(it) }).withPropertyName("catalogInputFiles")
    outputDirectory.set(generatedCatalog)
    workingDir(rootProject.projectDir)
    doFirst {
        val args = mutableListOf("python3", "tools/catalog/prepare_assets.py", "--output-dir", generatedCatalog.get().asFile.absolutePath)
        if(catalogInput.isPresent) args.addAll(listOf("--catalog-dir", rootProject.file(catalogInput.get()).absolutePath))
        commandLine(args)
    }
}
val prepareCatalogTestAssets = tasks.register<CatalogAssetsTask>("prepareCatalogTestAssets") {
    inputs.files(rootProject.fileTree("tools/catalog") { exclude("**/__pycache__/**") }); inputs.file(rootProject.file("core/data/src/main/assets/schema/catalog-v1.sql"))
    outputDirectory.set(generatedTestCatalog)
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/catalog/prepare_assets.py", "--test-assets", "--output-dir", generatedTestCatalog.get().asFile.absolutePath)
}
val requireProductionCatalog = tasks.register("requireProductionCatalog") {
    doLast { check(catalogInput.isPresent) { "Release requires -PcatalogDir=<validated full artifact directory>. Run tools/catalog/export_catalog.py first." } }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareCatalogAssets) }
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(prepareCatalogTestAssets) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(requireProductionCatalog) }
dependencies {
    debugImplementation(libs.media3.exoplayer)
    debugImplementation(libs.media3.hls)
    debugImplementation(libs.media3.okhttp)
    implementation(project(":feature:downloads")); implementation(project(":feature:player")); implementation(project(":feature:catalog")); implementation(project(":core:model")); implementation(project(":core:data"))
    implementation(project(":core:network")); implementation(project(":core:media")); implementation(project(":core:ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose); implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose); implementation(libs.lifecycle.viewmodel.compose); implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.compose); implementation(libs.serialization.json)
    implementation(libs.coroutines.android); implementation(libs.biometric); implementation(libs.datastore.preferences)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test); testImplementation(project(":core:testing"))
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.media3.ui)
    androidTestImplementation(libs.media3.session)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.test.runner); androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.sqlite.bundled)
    debugImplementation(libs.compose.ui.test.manifest)
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareCatalogAssets) { it.outputDirectory }
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareCatalogTestAssets) { it.outputDirectory }
}

// Optional neutral source-lab media; normal/release builds do not require ffmpeg.
val sourceLabEnabled = providers.gradleProperty("sourceLab").map { it.toBoolean() }.orElse(false)
val prepareSourceFixtures = tasks.register<CatalogAssetsTask>("prepareSourceFixtures") {
    inputs.file(rootProject.file("tools/sources/generate_fixtures.py"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sourceLabAssets"))
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/sources/generate_fixtures.py", "--output-dir", outputDirectory.get().asFile.resolve("source-lab").absolutePath)
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    if(sourceLabEnabled.get()) variant.sources.assets?.addGeneratedSourceDirectory(prepareSourceFixtures) { it.outputDirectory }
}

val sourceSamples = providers.gradleProperty("sourceSamplesDir")
val prepareSourceSamples = tasks.register<CatalogAssetsTask>("prepareSourceSamples") {
    inputs.file(rootProject.file("tools/sources/prepare_samples.py"))
    if(sourceSamples.isPresent) inputs.files(listOf("samples.json", "inventory.json").map { rootProject.file(sourceSamples.get()).resolve(it) })
    outputDirectory.set(layout.buildDirectory.dir("generated/sourceSampleAssets"))
    workingDir(rootProject.projectDir)
    doFirst {
        check(sourceSamples.isPresent)
        commandLine("python3", "tools/sources/prepare_samples.py", "--sample-dir", rootProject.file(sourceSamples.get()).absolutePath, "--output-dir", outputDirectory.get().asFile.absolutePath)
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    if(sourceSamples.isPresent) variant.sources.assets?.addGeneratedSourceDirectory(prepareSourceSamples) { it.outputDirectory }
}
