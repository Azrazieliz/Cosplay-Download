plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.azrael.galleryflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.azrael.galleryflow"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
        testInstrumentationRunner = "android.app.Instrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("com.github.junrar:junrar:8.1.1")
    testImplementation("junit:junit:4.13.2")
}
