plugins { alias(libs.plugins.android.library) }
android {
    namespace = "tech.imitravanu.eroflix.core.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(":app:prepareCatalogTestAssets") }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.sqlite.bundled)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootProject.file("app/build/generated/catalogTestAssets").absolutePath)
}
