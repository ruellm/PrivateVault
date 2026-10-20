# Each test is a plain executable registered with CTest. A nonzero exit code
# means failure. See tests/core/check.h for the assertion helper. Every test
# runs in its own scratch directory under the build tree.

set(PV_FIXTURES_DIR "${CMAKE_SOURCE_DIR}/tests/fixtures/v1")

function(pv_add_test name)
    add_executable(${name} ${name}.cpp)
    target_link_libraries(${name} PRIVATE vault_core pv_desktop)
    target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_definitions(${name} PRIVATE
        PV_FIXTURES_DIR="${PV_FIXTURES_DIR}"
        PV_CLI_PATH="$<TARGET_FILE:pv>"
        PV_CLI_PATH_W=L"$<TARGET_FILE:pv>"
    )
    set(workdir "${CMAKE_BINARY_DIR}/testwork/${name}")
    file(MAKE_DIRECTORY "${workdir}")
    add_test(NAME ${name} COMMAND ${name} WORKING_DIRECTORY "${workdir}")
endfunction()

pv_add_test(test_init)
pv_add_test(test_deterministic)
pv_add_test(test_roundtrip)
pv_add_test(test_negative)
pv_add_test(test_vault)
pv_add_test(test_io_faults)
pv_add_test(test_envelope)
pv_add_test(test_golden)
pv_add_test(test_large)
pv_add_test(test_bounded_memory)
pv_add_test(test_memory_source)
pv_add_test(test_item_header)
pv_add_test(test_item_objects)
pv_add_test(test_pin_stretch)
pv_add_test(test_envelope3)
pv_add_test(test_viewer_buffer)
pv_add_test(test_video_index)
set_tests_properties(test_large test_bounded_memory PROPERTIES LABELS "large" TIMEOUT 1800)

if(PV_TEST_HOOKS)
    pv_add_test(test_cli)
    set_tests_properties(test_cli PROPERTIES DEPENDS test_init)
else()
    message(STATUS "PV_TEST_HOOKS is OFF: test_cli is not built")
endif()

# Golden vector generator (not a test).
add_executable(make_vectors ../tools/make_vectors.cpp)
target_link_libraries(make_vectors PRIVATE vault_core)
target_include_directories(make_vectors PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
