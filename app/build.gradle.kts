plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.naladchik.notebook"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.naladchik.notebook"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.1"
    }

    // Один ключ подписи для всех сборок (Android Studio и GitHub):
    // новый APK ставится поверх старого и записи сохраняются.
    signingConfigs {
        create("notebook") {
            storeFile = file("notebook.keystore")
            storePassword = "naladchik"
            keyAlias = "notebook"
            keyPassword = "naladchik"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("notebook")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("notebook")
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
