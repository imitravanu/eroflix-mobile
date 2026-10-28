pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "EroflixMobile"
include(":feature:downloads", ":feature:player", ":feature:catalog", ":app", ":core:model", ":core:testing", ":core:data", ":core:network", ":core:media", ":core:ui")
