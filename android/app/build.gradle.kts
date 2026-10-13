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
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.privatevault.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
        release {
            isMinifyEnabled = false
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
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
