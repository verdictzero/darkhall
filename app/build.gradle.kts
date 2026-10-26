plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.verdictzero.darkhall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.verdictzero.darkhall"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // Release key from the environment (the release workflow's secrets). Without one, release
    // builds are signed with the debug key so the APK still installs.
    val releaseKeystore = System.getenv("DARKHALL_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("DARKHALL_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DARKHALL_KEY_ALIAS")
            keyPassword = System.getenv("DARKHALL_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "3g"
            // One JVM per test class: an Android process has one Application, but Robolectric
            // makes a new one per test, and static caches (the view-model factory's, notably)
            // would otherwise hand later tests the first test's.
            it.forkEvery = 1
            // Lets captureToImage() work under Robolectric (screenshot test).
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // End-to-end test input: -Pdarkhall.sample=/path/to/file.orf (skipped when absent).
            it.systemProperty("darkhall.sample", (project.findProperty("darkhall.sample") ?: "") as String)
            it.systemProperty("darkhall.outDir", (project.findProperty("darkhall.outDir") ?: "") as String)
            // Another camera's raw for OtherFormatsSmokeTest: -Pdarkhall.otherSample=/path/to/file.arw
            it.systemProperty("darkhall.otherSample", (project.findProperty("darkhall.otherSample") ?: "") as String)
            it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }

    androidResources {
        // Look tables are already zlib-compressed.
        noCompress += "dlut"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
