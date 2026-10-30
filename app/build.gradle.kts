plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub Actions run gets a monotonically increasing versionCode so
// Obtainium recognizes newer releases without a manual version bump.
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val signingKeyPath = System.getenv("CI_SIGNING_KEYSTORE")
val signingPassword = System.getenv("CI_SIGNING_PASSWORD")

android {
    namespace = "io.github.codeg0blin.videowallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.imadraude.videowallpaper"
        minSdk = 26
        targetSdk = 34
        versionCode = ciRunNumber?.let { 10000 + it } ?: 10001
        versionName = ciRunNumber?.let { "1.3.1.$it" } ?: "1.3.1"
    }

    signingConfigs {
        if (!signingKeyPath.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            create("ciRelease") {
                storeFile = file(signingKeyPath)
                storePassword = signingPassword
                keyAlias = "video-wallpaper"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            if (signingConfigs.findByName("ciRelease") != null) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
    dependenciesInfo {
       includeInApk = false
       includeInBundle = false
   }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // Stable Media3 implementation, kept opt-in to benchmark against MediaPlayer.
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    testImplementation("junit:junit:4.13.2")
}
