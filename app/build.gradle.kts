import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.anees.caller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anees.caller"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "UPDATE_REPO", "\"anees-dahot/caller-app\"")
    }

    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = file(keyProps["storeFile"] as String)
                storePassword = keyProps["storePassword"] as String
                keyAlias = keyProps["keyAlias"] as String
                keyPassword = keyProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keyProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
