plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from gradle.properties, not GitHub Actions run numbers.
// Encode major.minor.patch as an increasing Android versionCode:
// 1.4.0 -> 10400, above the earlier automatically numbered 1.3.1 builds.
val appVersion = providers.gradleProperty("appVersion").get()
val versionMatch = Regex("""(\\d+)\\.(\\d+)\\.(\\d+)""").matchEntire(appVersion)
    ?: error("appVersion must be major.minor.patch")
val (major, minor, patch) = versionMatch.destructured
require(minor.toInt() in 0..99 && patch.toInt() in 0..99) {
    "Minor and patch versions must fit within 0..99 for Android versionCode."
}
val appVersionCode = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
val signingKeyPath = System.getenv("CI_SIGNING_KEYSTORE")
val signingPassword = System.getenv("CI_SIGNING_PASSWORD")

android {
    namespace = "io.github.codeg0blin.videowallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.imadraude.videowallpaper"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
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
