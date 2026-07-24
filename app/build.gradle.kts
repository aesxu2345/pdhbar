import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val verifyUIKitInsightAar by tasks.registering {
    val aar = rootProject.layout.projectDirectory.file("uikit/UIKitInsight-release.aar")
    val checksums = rootProject.layout.projectDirectory.file("uikit/SHA256SUMS")
    inputs.file(aar)
    inputs.file(checksums)

    doLast {
        val expected = checksums.asFile.readLines()
            .first { it.trim().endsWith("uikit/UIKitInsight-release.aar") }
            .trim()
            .split(Regex("\\s+"), limit = 2)
            .first()
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(aar.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "UIKitInsight AAR 校验失败：期望 $expected，实际 $actual。请更新 uikit 制品及 SHA256SUMS。"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyUIKitInsightAar)
}

android {
    namespace = "com.pdyy.pdhbar"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.pdyy.pdhbar"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.google.mlkit.barcode.scanning)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Versioned local UIKit AAR and its integration guide live together under /uikit.
    implementation("org.mozilla.geckoview:geckoview:152.0.20260713164047")
    implementation(files(rootProject.file("uikit/UIKitInsight-release.aar")))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
