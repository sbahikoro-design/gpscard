plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ci.bsinaly.callmagic"
    compileSdk = 34

    defaultConfig {
        applicationId = "ci.bsinaly.callmagic"
        minSdk = 21
        targetSdk = 34
        versionCode = 41
        versionName = "4.1"
    }

    buildFeatures {
        buildConfig = true
    }

    // Clé de signature fixe (BSINALY) : mêmes signatures d'une version à l'autre → mises à jour sans désinstaller
    signingConfigs {
        create("bsinaly") {
            storeFile = file("../keystore/bscallmagic.jks")
            storePassword = "BsCallMagic-Odienne-2026"
            keyAlias = "bscallmagic"
            keyPassword = "BsCallMagic-Odienne-2026"
        }
    }

    // Deux éditions : « standard » sans aucune autorisation SMS (acceptée par Play Protect),
    // « pont » avec le pont SMS (seulement pour les téléphones qui servent de pont)
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "SMS", "false")
        }
        create("pont") {
            dimension = "edition"
            versionNameSuffix = "-pont"
            buildConfigField("boolean", "SMS", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bsinaly")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
