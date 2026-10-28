plugins { alias(libs.plugins.kotlin.jvm); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    api(project(":core:model"))
}
