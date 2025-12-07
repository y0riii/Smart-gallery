plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" // Use the appropriate KSP release
}

android {
    namespace = "com.example.kotlin_test"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.kotlin_test"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Material3
    implementation(libs.material3)

    // Coil (Compose)
    implementation(libs.coil.compose.v260)

    // Optional: lifecycle + viewmodel
    implementation(libs.androidx.lifecycle.runtime.ktx.v2100)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // OCR / ML Kit
    implementation(libs.text.recognition)
    implementation(libs.vision.common)
    implementation(libs.play.services.mlkit.text.recognition.common)
    implementation(libs.play.services.mlkit.text.recognition)

    // SQLite / Room (CLEANUP: Removed redundant entries, keeping the explicit v261)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlite.android)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    // Coroutines (if not already in your catalog)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}