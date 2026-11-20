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
        versionCode = 50
        versionName = "5.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Clé de signature BSINALY : jamais dans le dépôt. Elle est fournie à la compilation par les
    // secrets GitHub (BSCM_KEYSTORE, BSCM_PASSWORD) et n'existe que le temps de la compilation.
    val ksFile = System.getenv("BSCM_KEYSTORE_FILE")
    val ksPass = System.getenv("BSCM_PASSWORD")
    val signed = !ksFile.isNullOrEmpty() && !ksPass.isNullOrEmpty()
    signingConfigs {
        if (signed) create("bsinaly") {
            storeFile = file(ksFile!!)
            storePassword = ksPass
            keyAlias = "bscallmagic"
            keyPassword = ksPass
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
            // Sans la clé (compilation de contrôle), on signe en « debug » ; ces APK ne sont jamais publiés
            signingConfig = signingConfigs.getByName(if (signed) "bsinaly" else "debug")
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
