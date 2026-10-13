# JNI bridge. One shared library that statically links the core and libsodium.
# Added from the root CMakeLists.txt when building for Android.

add_library(privatevault_jni SHARED
    jni_bridge.cpp
)

target_link_libraries(privatevault_jni PRIVATE vault_core log)

# Hide everything except the JNI entry points, including the symbols of the
# static libraries linked in.
set_target_properties(privatevault_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_link_options(privatevault_jni PRIVATE "-Wl,--exclude-libs,ALL")
