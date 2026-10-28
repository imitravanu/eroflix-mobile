plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android { namespace = "tech.imitravanu.eroflix.feature.player"; buildFeatures { compose = true } }
dependencies {
    implementation(project(":core:model")); implementation(project(":core:media")); implementation(project(":core:network")); implementation(project(":core:ui"))
    implementation(libs.media3.ui); implementation(libs.activity.compose); implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
}
