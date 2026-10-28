plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android { namespace = "tech.imitravanu.eroflix.feature.catalog"; buildFeatures { compose = true } }
dependencies {
    implementation(project(":core:model")); implementation(project(":core:ui"))
    implementation(libs.lifecycle.viewmodel.ktx); implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose); implementation(libs.coroutines.android)
    implementation(libs.activity.compose)
}
