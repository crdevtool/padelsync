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
    //
    // An App Store build runs on real devices only, so the release workflow
    // passes -PappleDevicesOnly=true to leave the simulator builds out and
    // save Mac build time. Everything else builds all five.
    val devicesOnly = providers.gradleProperty("appleDevicesOnly").orNull == "true"
    val xcframework = XCFramework("PadelSyncCore")
    listOfNotNull(
        iosArm64(),
        watchosArm64(),
        watchosDeviceArm64(),
        if (devicesOnly) null else iosSimulatorArm64(),
        if (devicesOnly) null else watchosSimulatorArm64(),
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
