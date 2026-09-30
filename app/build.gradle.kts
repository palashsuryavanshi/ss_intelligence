import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin through its built-in support, so the
    // org.jetbrains.kotlin.android plugin is not applied here. KSP is declared
    // in the root project (apply false) to pin the Kotlin version that AGP's
    // built-in Kotlin resolves to, keeping it in lockstep with KSP and the
    // Compose compiler plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { versionProps.load(it) }
}
val appVersionCode = (versionProps.getProperty("versionCode") ?: "1").toInt()
val appVersionName = versionProps.getProperty("versionName") ?: "1.0.0-phase1"

android {
    namespace = "com.ssintelligence.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ssintelligence.app"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("androidTest") {
            // The migration test validates the migrated schema against the
            // exported JSON, which therefore has to be on the test device.
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// Exported Room schemas make migrations reviewable and testable (§9).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose BOM + Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room (KSP annotation processing)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Background indexing
    implementation(libs.androidx.work.runtime)

    // Settings persistence
    implementation(libs.androidx.datastore.preferences)

    // On-device OCR (bundled model: fully offline, no model download).
    //
    // The transitive datatransport library stays on the classpath because ML
    // Kit's pipeline hard-links against it (CCTDestination) and removing it
    // crashes OCR at runtime. Network access is denied structurally instead:
    // AndroidManifest.xml strips the INTERNET permission from the merged
    // manifest with tools:node="remove", so nothing can be sent even if some
    // code path tried.
    implementation(libs.mlkit.text.recognition)

    // Image loading (thumbnails from MediaStore content URIs)
    implementation(libs.coil.compose)

    // Pinned explicitly. androidx.room:room-migration 2.8.4 is compiled against
    // kotlinx-serialization 1.8.1, and Gradle's consistent resolution was
    // otherwise settling on 1.7.3. The mismatch only surfaces as an
    // AbstractMethodError at runtime — including during a real database
    // migration on a user's device — so the version Room needs is the version
    // the app gets.
    implementation(libs.kotlinx.serialization.json)

    // Local JVM unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests (require a device/emulator)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}

