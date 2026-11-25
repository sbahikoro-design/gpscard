plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bsinaly.bsnotifspeak"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bsinaly.bsnotifspeak"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    signingConfigs {
        create("bs") {
            // Clé fixe : les prochaines versions s'installeront par-dessus sans désinstaller
            storeFile = file("bs-notif-speak.jks")
            storePassword = "bsinaly2026"
            keyAlias = "bsnotifspeak"
            keyPassword = "bsinaly2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bs")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
