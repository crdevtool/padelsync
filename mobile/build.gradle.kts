import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.padelsync.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The ID the app is installed and published under. It is not the Kotlin
        // package: the code stays in the namespace above.
        applicationId = "com.crdevtool.padelsync"
        minSdk = 26
        targetSdk = 36
        // The phone and watch apps share one app ID, so their version codes
        // must differ; PlayRelease keeps them apart.
        versionCode = PlayRelease.versionCode(project, FormFactor.PHONE)
        versionName = PlayRelease.versionName(project)
        // The name under the icon; see PlayRelease.sideBySide.
        manifestPlaceholders["appLabel"] = PlayRelease.appLabel(project)
    }

    // Stripping native libraries and reading their symbols needs an NDK. A
    // machine that has one says where; a machine without one still builds,
    // and its bundles simply carry no symbols.
    PlayRelease.ndk()?.let {
        ndkPath = it.path
        ndkVersion = it.version
    }

    signingConfigs {
        // A fixed debug key, so a newer test build installs over an older one
        // without uninstalling first. Never used for store releases.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Google Play upload key. It exists only when the environment
        // supplies it; it is never stored in this repository.
        PlayRelease.uploadKey(project)?.let { key ->
            create("release") {
                storeFile = key.storeFile
                storePassword = key.storePassword
                keyAlias = key.keyAlias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // A separate app ID lets a test build sit beside the Play Store
            // version on the same device; see PlayRelease.sideBySide.
            if (PlayRelease.sideBySide(project)) applicationIdSuffix = ".test"
        }
        release {
            // Shrunk and optimised, as store builds should be. The build
            // server runs the same emulator tests on this build as on the
            // debug one, to show that it behaves the same.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the upload key when the environment describes one
            // (see buildSrc/src/main/kotlin/PlayRelease.kt); unsigned
            // otherwise. Never with the debug key.
            signingConfig = signingConfigs.findByName("release")
            // Puts the symbol table of the native code that comes with the
            // Android libraries into the bundle. Google Play then reads
            // native crash reports by function name, and stops warning that
            // the bundle has native code without debug symbols.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":androidkit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}

apply(from = rootProject.file("gradle/export-classpath.gradle.kts"))
