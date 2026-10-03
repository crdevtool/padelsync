import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Android and Wear OS consume the JVM build. It is also where the tests
    // run on any machine, including Windows.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iPhone and Apple Watch. Built on macOS only.
    val xcframework = XCFramework("PadelSyncCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        watchosArm64(),
        watchosDeviceArm64(),
        watchosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "PadelSyncCore"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
