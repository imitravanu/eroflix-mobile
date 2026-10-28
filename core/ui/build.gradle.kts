plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android { namespace = "tech.imitravanu.eroflix.core.ui"; buildFeatures { compose = true } }
dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
}
