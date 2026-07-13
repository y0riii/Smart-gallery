import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.devtools.ksp") version "2.2.21-2.0.4"
}

// Release-signing credentials are read from keystore.properties at the project root (kept OUT of
// git — see .gitignore). If that file is absent (e.g. a fresh clone without the keystore), the
// release build falls back to debug signing so the project still builds — see buildTypes.release.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "com.example.gallery"
    compileSdk = 36

    defaultConfig {
        // Permanent installed identity (Play/website). Kept separate from `namespace` (which stays
        // com.example.gallery), so no source files or imports need to move. Do not change post-release.
        applicationId = "com.smartgallery.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only define a "release" config when the keystore is present, so the project still builds
        // without it (falling back to debug signing below). Fill keystore.properties from the
        // committed keystore.properties.template.
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the real release key when keystore.properties is present; otherwise fall back to
            // the debug key so the build never breaks. Ship public releases only with the real key.
            signingConfig = if (hasReleaseKeystore)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }

    // Keep ONNX models (.ort) UNCOMPRESSED in the APK so they can be memory-mapped directly from it
    // (ModelAssets.mappedModel) instead of being copied to internal storage — no on-disk duplication
    // and no large Java-heap allocation at load. .ort is already near-incompressible, so the APK size
    // impact is minimal.
    androidResources {
        noCompress += "ort"
    }
}

// Export Room schemas to a tracked directory so future schema changes can ship real
// migrations instead of silently wiping the user's indexed database. The exported JSON
// also becomes the source for Room's automated migration testing.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.coil.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.text.recognition)
    implementation(libs.tesseract4android)
    implementation(libs.face.detection)
    implementation(libs.androidx.work.runtime.ktx)

    // Media3 / ExoPlayer — premium video playback (buffering, formats, smooth seeking)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
