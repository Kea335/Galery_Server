import java.util.Properties

plugins {
    // AGP 9 brings its own Kotlin integration — applying
    // org.jetbrains.kotlin.android on top is an error now.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kadr.app"
    // AndroidX 1.19/Compose 1.12 refuse to be consumed below 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kadr.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    /**
     * Release signing.
     *
     * The keystore and its passwords are read from `keystore.properties`, which
     * is not checked in. A signing key committed to a repository is a key held
     * by everyone who ever clones it, and on Android that is not recoverable:
     * the key *is* the app's identity, so a leaked one can be used to publish
     * something that installs straight over this one.
     *
     * When the file is absent the release build still runs and produces an
     * unsigned APK. A machine without the key can compile, minify and test —
     * it simply cannot ship. Failing the whole build instead would make the key
     * a prerequisite for `assembleRelease` on CI, which it is not.
     */
    val signingProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use(::load)
    }

    signingConfigs {
        if (!signingProperties.isEmpty) {
            val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            val missing = required.filter { signingProperties.getProperty(it).isNullOrBlank() }
            require(missing.isEmpty()) {
                "keystore.properties is missing: ${missing.joinToString()}. " +
                    "See keystore.properties.example."
            }

            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // Null when there is no keystore.properties: the APK comes out
            // unsigned rather than the build coming out broken.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // Exported schemas are what the migration test reads.
    arg("room.schemaLocation", "$projectDir/schemas")
}

androidComponents {
    // MigrationTestHelper looks the schemas up as instrumentation assets.
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // §15: the timeline is read a page at a time, so a 10,000-photo library
    // costs the same to open as a 200-photo one.
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    // AndroidJUnitRunner itself is not pulled in transitively — without this the
    // instrumentation dies with ClassNotFoundException before any test runs.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    // §17's five failure cases need a server that can be told to misbehave.
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.paging.testing)
}
