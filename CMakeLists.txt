cmake_minimum_required(VERSION 3.28)

# Android ABI to vcpkg triplet mapping. Must run before project() because the
# vcpkg toolchain file reads VCPKG_TARGET_TRIPLET when project() loads it.
if(ANDROID_ABI AND NOT VCPKG_TARGET_TRIPLET)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(VCPKG_TARGET_TRIPLET "arm64-android")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(VCPKG_TARGET_TRIPLET "x64-android")
    else()
        message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
    endif()
endif()
if(ANDROID_NDK AND NOT DEFINED ENV{ANDROID_NDK_HOME})
    set(ENV{ANDROID_NDK_HOME} "${ANDROID_NDK}")
endif()
if(ANDROID_NDK AND NOT VCPKG_CHAINLOAD_TOOLCHAIN_FILE)
    set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${ANDROID_NDK}/build/cmake/android.toolchain.cmake")
endif()

project(PrivateVault VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(PV_BUILD_CLI "Build the desktop command line tool" ON)
option(PV_BUILD_TESTS "Build the host test programs" ON)
option(PV_TEST_HOOKS "Compile test-only options into the CLI" OFF)

if(ANDROID)
    set(PV_BUILD_CLI OFF)
    set(PV_BUILD_TESTS OFF)
endif()

if(MSVC)
    add_compile_options(/W4 /permissive- /utf-8)
else()
    add_compile_options(-Wall -Wextra -Wpedantic)
endif()

find_package(unofficial-sodium CONFIG REQUIRED)

add_subdirectory(src/vault_core)

if(PV_BUILD_CLI)
    add_subdirectory(src/desktop_cli)
endif()

if(PV_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests/core)
endif()

if(ANDROID)
    add_subdirectory(android/app/src/main/cpp)
endif()
