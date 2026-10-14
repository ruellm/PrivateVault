plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// vcpkg supplies libsodium. VCPKG_ROOT must point at a checkout of the registry
// at the baseline commit named in vcpkg.json at the repository root.
val vcpkgRoot: String = System.getenv("VCPKG_ROOT")
    ?: throw GradleException("Set VCPKG_ROOT to the vcpkg checkout (see README.md).")

android {
    namespace = "com.privatevault.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.privatevault.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // This toolchain argument overrides the one AGP adds. The root
                // CMakeLists.txt chainloads the NDK toolchain through vcpkg.
                arguments += listOf(
                    "-DCMAKE_TOOLCHAIN_FILE=${vcpkgRoot.replace('\\', '/')}/scripts/buildsystems/vcpkg.cmake",
                    "-DANDROID_STL=c++_static",
                    "-DVCPKG_INSTALL_OPTIONS=--clean-after-build"
                )
            }
        }
    }

    buildTypes {
        debug {
            // Test hooks (slow source, fault injection, plaintext readback for
            // tests) exist only in this variant's native library.
            externalNativeBuild {
                cmake {
                    arguments += "-DPV_ANDROID_DEBUG_HOOKS=ON"
                }
            }
        }
        release {
            isMinifyEnabled = false
        }
        // Non-debuggable build with optimized native code for the Phase 2
        // performance measurements. Signed with the debug key so it installs
        // on test devices; run-as does not work on it, results come from logcat.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Golden vectors are packaged as assets of the instrumented test APK only.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("../../tests/fixtures/v1")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Use of a @RestrictTo API in app code fails the build.
        error += "RestrictedApi"
        abortOnError = true
    }
}

dependencies {
    // CameraX (Phase 2). Versions pinned; metadata in docs/DEPENDENCIES.md.
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // ComponentActivity is the LifecycleOwner CameraX binds to.
    implementation("androidx.activity:activity:1.10.1")
    // Video feasibility prototype, debug variant only.
    debugImplementation("androidx.camera:camera-video:1.6.2")
    debugImplementation("androidx.media3:media3-muxer:1.11.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
