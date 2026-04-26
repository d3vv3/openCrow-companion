plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.opencrow.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.opencrow.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("debugKey") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("releaseKey") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
            val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val hasReleaseKey = System.getenv("RELEASE_KEYSTORE_PATH") != null
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("releaseKey")
            } else {
                signingConfigs.getByName("debugKey")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

composeCompiler {
    stabilityConfigurationFile = project.layout.projectDirectory.file("compose-stability.conf")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.gson)

    // Image loading
    implementation(libs.coil.compose)

    // CameraX + ML Kit for QR scanning
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)

    // WorkManager for heartbeat
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)
}
