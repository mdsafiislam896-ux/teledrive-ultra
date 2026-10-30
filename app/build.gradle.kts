plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val telegramApiId = providers.environmentVariable("TELEGRAM_API_ID").orElse("").get()
val telegramApiHash = providers.environmentVariable("TELEGRAM_API_HASH").orElse("").get()

android {
    namespace = "com.safi.teledrive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.safi.teledrive"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "TELEGRAM_API_ID", "\"$telegramApiId\"")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        val keyPath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!keyPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keyPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.annotation:annotation:1.9.1")
}
