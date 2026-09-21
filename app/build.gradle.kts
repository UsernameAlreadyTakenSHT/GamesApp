plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.usernamealreadytakensht.games"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.usernamealreadytakensht.games"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // `-PappIdSuffix=.foo` installs a side-by-side debug build (parallel work-streams on one emulator).
            applicationIdSuffix = (project.findProperty("appIdSuffix") as String?)?.takeIf { it.isNotBlank() }
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Lc0 networks (.lc0 = gzipped protobuf) are already compressed; keep them stored as-is so
        // they can be sized and streamed. They are not named .gz because AGP would un-gzip them.
        noCompress.add("lc0")
        noCompress.add("onnx")
    }
    splits {
        // One APK per ABI: the engines weigh ~280 MB per architecture, so a universal APK
        // would be twice that. Android Studio picks the right split for the target device.
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        // Extract libstockfish.so to disk (nativeLibraryDir) so it can be run as a process.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets {
        getByName("main") {
            // OpenTafl's engine core (Java), copied by opentafl/build.sh, plus our stubs for the
            // few desktop-UI classes it references. It referees and plays hnefatafl in-process.
            java.srcDirs("../opentafl/java", "../opentafl/stubs")
        }
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.chesslib)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.json) // real org.json (android.jar only has stubs)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}