plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android { namespace = "tech.imitravanu.eroflix.feature.downloads"; buildFeatures { compose = true } }
dependencies {
    implementation(project(":core:model")); implementation(project(":core:media")); implementation(project(":core:ui"))
    implementation(libs.lifecycle.runtime.compose); implementation(libs.coroutines.android); implementation(libs.activity.compose)
}
