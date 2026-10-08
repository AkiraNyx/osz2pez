plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.akiranyx.osu2phira"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.akiranyx.osu2phira"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
