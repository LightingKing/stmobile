pluginManagement {
    repositories {
        mavenCentral()
        providers.gradleProperty("androidMirror").orNull?.let { maven(it) }
        google(); gradlePluginPortal()
    }
    resolutionStrategy { eachPlugin {
        if (requested.id.id == "org.jetbrains.kotlin.kapt") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
        if (requested.id.id == "com.android.application") useModule("com.android.tools.build:gradle:${requested.version}")
        if (requested.id.id == "com.android.compose.screenshot") useModule("com.android.compose.screenshot:screenshot-test-gradle-plugin:${requested.version}")
    } }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        providers.gradleProperty("androidMirror").orNull?.let { maven(it) }
        google()
    }
}
rootProject.name = "PocketTavern"
include(":app")
