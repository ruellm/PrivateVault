add_library(vault_core STATIC
    src/pv_init.cpp
    src/fs.cpp
    src/format.cpp
    src/object.cpp
    src/vault.cpp
    src/streams.cpp
    src/item.cpp
    src/pin.cpp
    src/envelope3.cpp
    src/viewer.cpp
    src/video_index.cpp
    src/segment.cpp
    src/audio_queue.cpp
)

target_include_directories(vault_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_compile_definitions(vault_core PUBLIC SODIUM_STATIC=1)
target_link_libraries(vault_core PUBLIC unofficial-sodium::sodium)
