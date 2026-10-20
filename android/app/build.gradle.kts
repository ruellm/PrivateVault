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
        versionCode = 6
        versionName = "0.6.0"
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

    // Golden vectors and the EXIF orientation fixtures are packaged as assets
    // of the instrumented test APK only. Both directories carry a README,
    // which the asset merge would report as a duplicate, so READMEs are not
    // packaged (the default ignore pattern plus README.md).
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("../../tests/fixtures/v1", "../../tests/fixtures/exif")
        }
    }
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!README.md"
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
    // camera-view declares camera-video, which brings media3 1.9.0 and guava
    // into every variant. The app uses PreviewView only, so camera-video is
    // kept out (docs/PHASE3B_DESIGN.md, P3-10).
    implementation("androidx.camera:camera-view:1.6.2") {
        exclude(group = "androidx.camera", module = "camera-video")
    }
    // ComponentActivity is the LifecycleOwner CameraX binds to.
    implementation("androidx.activity:activity:1.10.1")
    // ExoPlayer for the video viewer (docs/PHASE3D_DESIGN.md Section 3). It
    // brings media3-extractor, -decoder, -datasource, -database, -container,
    // and -common; its WAKE_LOCK permission is removed in the manifest.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // Video prototype, debug variant only: the muxer for the recording and
    // the extractor declared for the key-frame investigation.
    debugImplementation("androidx.media3:media3-muxer:1.11.1")
    debugImplementation("androidx.media3:media3-extractor:1.11.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
