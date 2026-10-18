plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "app.pockettavern"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.pockettavern"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)
    useLibrary("android.test.mock", false)
    signingConfigs {
        create("personal") {
            System.getenv("POCKET_SIGNING_FILE")?.let { storeFile = file(it) }
            storePassword = System.getenv("POCKET_SIGNING_PASSWORD")
            keyAlias = "pocket-tavern"
            keyPassword = System.getenv("POCKET_SIGNING_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (System.getenv("POCKET_SIGNING_FILE") != null) signingConfig = signingConfigs.getByName("personal")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.tracing:tracing:1.2.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-ripple:1.7.6")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
tasks.withType<Test>().configureEach {
    inputs.property("liveVenice", System.getenv("POCKET_LIVE_TESTS") == "true")
    inputs.property("liveMedia", System.getenv("POCKET_LIVE_MEDIA") == "true")
}
