plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Default server for every build; for a local server pass -PsanefeedServerUrl=http://127.0.0.1:8787
// (debug builds allow plain HTTP) and run `adb reverse tcp:8787 tcp:8787`.
val serverUrl = providers.gradleProperty("sanefeedServerUrl").getOrElse("https://api.sanefeed.byhan.id")

// Release versioning: CI passes -PversionName=<tag without v> and -PversionCode=<major*10000+minor*100+patch>.
val appVersionName = providers.gradleProperty("versionName").getOrElse("1.0.0")
val appVersionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)

// Release signing, from Gradle properties (-P or ~/.gradle/gradle.properties) or environment variables.
// Without SANEFEED_KEYSTORE_PATH, release builds are left unsigned.
fun signingValue(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
val releaseKeystorePath = signingValue("SANEFEED_KEYSTORE_PATH")

// -PsanefeedDebugSuffix=false builds debug as plain app.sanefeed, to update a debug install made before the suffix.
val debugSuffix = providers.gradleProperty("sanefeedDebugSuffix").map(String::toBoolean).getOrElse(true)

android {
    namespace = "app.sanefeed"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sanefeed"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = signingValue("SANEFEED_KEYSTORE_PASSWORD")
                keyAlias = signingValue("SANEFEED_KEY_ALIAS")
                keyPassword = signingValue("SANEFEED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug and a release install live side by side on one phone.
            if (debugSuffix) {
                applicationIdSuffix = ".debug"
                versionNameSuffix = "-debug"
            }
        }
        release {
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
