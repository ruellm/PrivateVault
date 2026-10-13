# Desktop key provider as a small library so the host tests can call it
# directly, plus the command line tool.

add_library(pv_desktop STATIC
    envelope.cpp
)
target_include_directories(pv_desktop PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(pv_desktop PUBLIC vault_core)

add_executable(pv
    main.cpp
    password.cpp
)
target_link_libraries(pv PRIVATE pv_desktop)

if(PV_TEST_HOOKS)
    target_compile_definitions(pv PRIVATE PV_TEST_HOOKS=1)
endif()
