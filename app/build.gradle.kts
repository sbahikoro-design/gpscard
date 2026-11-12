plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ci.bsinaly.irscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "ci.bsinaly.irscan"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signée avec la clé debug pour pouvoir l'installer directement
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
