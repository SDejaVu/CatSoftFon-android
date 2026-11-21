import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing - same pattern as the official linphone-android app's own
// app/build.gradle.kts (keystore.properties at the project root, loaded here, values wired
// into a "release" signingConfig). Keeping actual passwords in a file that's gitignored,
// rather than in this build script, is standard practice: see keystore.properties.example at
// the repo root plus installer/build-release-apk.ps1, which creates your real
// keystore.properties for you the first time you run it.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.catsoftfon.sip"
    // Google Play requires new apps to target Android 16 (API 36) as of Aug 31 2026 - was 34.
    // Bumped here so the app can be submitted; if linphone-sdk-android 5.6.x doesn't yet compile
    // cleanly against compileSdk 36 on your machine, either pin a newer linphone-sdk-android
    // version above, or file Play Console's target-level extension request (good to Nov 1 2026)
    // while that gets sorted out. Re-test calls/mic/notifications after this bump either way -
    // Android 15/16 changed foreground-service and notification behavior.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.catsoftfon.sip"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            val keyStorePath = keystoreProperties.getProperty("storeFile")
            if (keyStorePath != null) {
                val keyStoreFile = rootProject.file(keyStorePath)
                if (keyStoreFile.exists()) {
                    storeFile = keyStoreFile
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only actually signed once keystore.properties + the keystore file it points to
            // both exist (see above) - until then, assembleRelease/bundleRelease fails with a
            // clear "keystore file not set" error rather than silently producing an unsigned
            // APK. Run installer/build-release-apk.ps1 once to set both up.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress Linphone's native libraries inside the APK: the download shrinks from
        // ~170 MB to roughly a third of that, at the cost of extracting them once on install.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Besides the universal APK (RuStore / GitHub), produce one APK per ABI - much smaller
    // downloads for GitHub Releases / IzzyOnDroid. All share the same versionCode.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

dependencies {
    // Linphone SDK - the shared SIP/RTP engine (same one the official Linphone Android app
    // uses). Pinned to the exact build this release was tested with, so builds are
    // reproducible ("5.6.+" would silently pick up newer 5.6.0 alpha snapshots). Coordinates confirmed against the official app's version catalog
    // (gradle/libs.versions.toml in github.com/BelledonneCommunications/linphone-android).
    implementation("org.linphone:linphone-sdk-android:5.6.0-alpha.213+9da09bbfde")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("com.google.code.gson:gson:2.11.0")
}
