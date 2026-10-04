include(FetchContent)
if(NOT MBEDTLS_SOURCE_DIR)
  FetchContent_Declare(sim_mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/mbedtls-3.6.2.zip
    URL_HASH SHA256=1b58da6d599a59a512c099ef0c1e0f556e73d4f4379024ff88c134f77960f5be)
  FetchContent_GetProperties(sim_mbedtls)
  if(NOT sim_mbedtls_POPULATED)
    FetchContent_Populate(sim_mbedtls)
  endif()
  set(MBEDTLS_SOURCE_DIR "${sim_mbedtls_SOURCE_DIR}")
endif()
if(NOT CJSON_SOURCE_DIR)
  FetchContent_Declare(sim_cjson
    URL https://github.com/DaveGamble/cJSON/archive/refs/tags/v1.7.19.zip
    URL_HASH SHA256=83fb7750db0601dca735868b8fb1da1318da2d2a1331a9a7da923cb891d26ea9)
  FetchContent_GetProperties(sim_cjson)
  if(NOT sim_cjson_POPULATED)
    FetchContent_Populate(sim_cjson)
  endif()
  set(CJSON_SOURCE_DIR "${sim_cjson_SOURCE_DIR}")
endif()
set(sim_mbed_sources asn1parse asn1write base64 bignum bignum_core bignum_mod bignum_mod_raw
    constant_time ecp ecp_curves ecdsa sha256 platform_util platform)
foreach(name IN LISTS sim_mbed_sources)
  list(APPEND SIM_CRYPTO_SOURCES "${MBEDTLS_SOURCE_DIR}/library/${name}.c")
endforeach()
add_library(sim-crypto STATIC ${SIM_CRYPTO_SOURCES} "${CJSON_SOURCE_DIR}/cJSON.c")
target_include_directories(sim-crypto PUBLIC "${MBEDTLS_SOURCE_DIR}/include"
  "${CJSON_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/../firmware-esp32/tests")
target_compile_definitions(sim-crypto PUBLIC MBEDTLS_CONFIG_FILE="mbedtls_test_config.h")

add_library(firmware-simulator
  src/sim_clock.cpp
  src/sim_rfid.cpp
  src/sim_stores.cpp
  src/scenario.cpp
  src/scenario_runner.cpp
  src/sim_ble.cpp
  ../firmware-esp32/components/offline-auth/offline_auth.cpp
  ../firmware-esp32/components/ble-sync/ble_gatt.cpp
)
target_include_directories(firmware-simulator PUBLIC include
  ../firmware-esp32/components/offline-auth/include
  ../firmware-esp32/components/ble-sync/include)
target_link_libraries(firmware-simulator PUBLIC firmware-core sim-crypto)
target_compile_definitions(firmware-simulator PUBLIC
  MIEZMERKER_FIXTURES_DIR="${CMAKE_SOURCE_DIR}/protocol/fixtures")
if(MSVC)
  target_compile_options(firmware-simulator PRIVATE /W4 /WX)
else()
  target_compile_options(firmware-simulator PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

add_executable(miezmerker-simulator main.cpp)
target_link_libraries(miezmerker-simulator PRIVATE firmware-core)
if(BUILD_TESTING)
  add_test(NAME simulator COMMAND miezmerker-simulator)
  set_tests_properties(simulator PROPERTIES PASS_REGULAR_EXPRESSION "core ready")
  add_test(NAME simulator-storage-unavailable COMMAND miezmerker-simulator --storage-unavailable)
  set_tests_properties(simulator-storage-unavailable PROPERTIES WILL_FAIL TRUE)
endif()

add_executable(scenario-runner src/scenario_runner_main.cpp)
target_link_libraries(scenario-runner PRIVATE firmware-simulator)
if(MSVC)
  target_compile_options(scenario-runner PRIVATE /W4 /WX)
else()
  target_compile_options(scenario-runner PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

if(BUILD_TESTING)
  add_executable(scenario-test tests/scenario_test.cpp)
  target_link_libraries(scenario-test PRIVATE firmware-simulator)
  if(MSVC)
    target_compile_options(scenario-test PRIVATE /W4 /WX)
  else()
    target_compile_options(scenario-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
  endif()
  add_test(NAME simulator-scenarios COMMAND scenario-test)

  file(GLOB SCENARIO_FILES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/scenarios/*.scenario")
  foreach(file ${SCENARIO_FILES})
    get_filename_component(name ${file} NAME_WE)
    add_test(NAME scenario-${name} COMMAND scenario-runner ${file})
  endforeach()
endif()
