plugins { alias(libs.plugins.android.library) }
android { namespace = "tech.imitravanu.eroflix.core.media" }
dependencies {
    implementation(project(":core:network"))
    implementation(libs.media3.okhttp)
    implementation(libs.media3.hls)
    implementation(project(":core:model"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.database)
    implementation(libs.media3.transformer)
    api(libs.media3.session)
    api(libs.media3.exoplayer)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
