# JNI bridge. One shared library that statically links the core and libsodium.
# Added from the root CMakeLists.txt when building for Android.

option(PV_ANDROID_DEBUG_HOOKS "Compile test hooks into the JNI library (debug variant only)" OFF)

add_library(privatevault_jni SHARED
    jni_bridge.cpp
)

target_link_libraries(privatevault_jni PRIVATE vault_core log)

if(PV_ANDROID_DEBUG_HOOKS)
    target_compile_definitions(privatevault_jni PRIVATE PV_DEBUG_HOOKS=1)
endif()

# Hide everything except the JNI entry points, including the symbols of the
# static libraries linked in.
set_target_properties(privatevault_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_link_options(privatevault_jni PRIVATE "-Wl,--exclude-libs,ALL")
