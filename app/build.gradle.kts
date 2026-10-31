plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Android's versionCode must keep increasing even though public versions use
// SemVer. CI run numbers provide a monotonic code independently of versionName.
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val appVersionName = providers.gradleProperty("appVersionName").get()
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
        versionName = appVersionName
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

    buildFeatures {
        viewBinding = true
    }
    dependenciesInfo {
       includeInApk = false
       includeInBundle = false
   }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    // Default video playback engine; MediaPlayer remains available as a fallback.
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    testImplementation("junit:junit:4.13.2")
}
