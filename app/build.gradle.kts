plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.asterion.videoflow"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.asterion.videoflow"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        ndk { abiFilters += if (project.findProperty("uiSmoke") == "true") listOf("x86_64") else listOf("arm64-v8a") }
    }
    signingConfigs {
        create("videoRelease") {
            val keyFile = System.getenv("VIDEOFLOW_KEYSTORE")
            if (!keyFile.isNullOrBlank()) {
                storeFile = file(keyFile)
                storePassword = System.getenv("VIDEOFLOW_PASSWORD") ?: ""
                keyAlias = "videoflow"
                keyPassword = System.getenv("VIDEOFLOW_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".preview07" }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("videoRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("dev.ffmpegkit-maintained:yt-dlp-android:2.0.2")
    implementation("org.jsoup:jsoup:1.18.1")
    testImplementation("junit:junit:4.13.2")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
