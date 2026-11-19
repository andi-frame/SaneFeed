plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release default server; override with -PsanefeedServerUrl=https://... or in the app.
val releaseServerUrl = providers.gradleProperty("sanefeedServerUrl").getOrElse("https://api.sanefeed.byhan.id")

android {
    namespace = "app.sanefeed"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sanefeed"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            // Reached on emulators and USB phones alike via `adb reverse tcp:8787 tcp:8787`.
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://127.0.0.1:8787\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$releaseServerUrl\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
