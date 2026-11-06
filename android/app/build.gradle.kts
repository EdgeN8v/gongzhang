import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String, fallback: String) = (localProps.getProperty(name) ?: fallback).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.gongzhang.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gongzhang.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.5.5"
        buildConfigField("String", "DEFAULT_SERVER", "\"" + localProp("gongzhang.defaultServer", "http://192.168.1.10:8100/") + "\"")
        buildConfigField("String", "LAN_SERVER", "\"" + localProp("gongzhang.lanServer", "") + "\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // same debug key every build, so each release installs over the previous one as an update
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
