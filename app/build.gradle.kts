plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.instantrecorder"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // The published app ID; Kotlin code keeps the shorter `app.instantrecorder` namespace.
        applicationId = "io.github.instantrecorder"
        minSdk = 31
        targetSdk = 36
        // CI passes the commit count so every build installs over the previous one.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "0.9.1"

        ndk {
            // Pixel phones are arm64-only.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // whisper.cpp is unusably slow at -O0, so always build native code optimized.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    // CI signs releases with the key from repository secrets (.github/workflows/build.yml). Without it,
    // e.g. a local `assembleRelease`, the debug key is used, which can't update a CI-installed app.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
