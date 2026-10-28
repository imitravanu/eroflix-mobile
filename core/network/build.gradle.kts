plugins { alias(libs.plugins.android.library) }
android { namespace = "tech.imitravanu.eroflix.core.network" }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
