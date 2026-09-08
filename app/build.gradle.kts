plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kareem.picbrain"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kareem.picbrain"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.0.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    val keystorePath = System.getenv("PICBRAIN_KEYSTORE_PATH")
    val signingPassword = System.getenv("PICBRAIN_SIGNING_PASSWORD")

    if (!keystorePath.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        signingConfigs {
            create("picbrain") {
                storeFile = file(keystorePath)
                storePassword = signingPassword
                keyAlias = "picbrain"
                keyPassword = signingPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }

        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("picbrain")
            }
            getByName("release") {
                signingConfig = signingConfigs.getByName("picbrain")
                isMinifyEnabled = false
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    ksp("androidx.room:room-compiler:2.6.1")
}
