import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    (localProps.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.futureyyy.tijing"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    flavorDimensions += "environment"

    productFlavors {
        create("prod") {
            dimension = "environment"
        }
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    defaultConfig {
        applicationId = "com.quizflow.practice"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "0.8.3-practice"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = prop("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = prop("RELEASE_STORE_PASSWORD")
                keyAlias = prop("RELEASE_KEY_ALIAS")
                keyPassword = prop("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (prop("RELEASE_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
