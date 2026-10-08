# Host verification of the exact ESP-IDF component with standard Mbed TLS/cJSON.
# Point source directories at Mbed TLS 3.6.x and cJSON 1.7.x; no board SDK required.
cmake_minimum_required(VERSION 3.22)
project(offline-auth-test LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
include(CTest)
include(FetchContent)
if(NOT MBEDTLS_SOURCE_DIR)
  FetchContent_Declare(auth_mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/mbedtls-3.6.2.zip
    URL_HASH SHA256=1b58da6d599a59a512c099ef0c1e0f556e73d4f4379024ff88c134f77960f5be)
  FetchContent_GetProperties(auth_mbedtls)
  if(NOT auth_mbedtls_POPULATED)
    FetchContent_Populate(auth_mbedtls)
  endif()
  set(MBEDTLS_SOURCE_DIR "${auth_mbedtls_SOURCE_DIR}")
endif()
if(NOT CJSON_SOURCE_DIR)
  FetchContent_Declare(auth_cjson
    URL https://github.com/DaveGamble/cJSON/archive/refs/tags/v1.7.19.zip
    URL_HASH SHA256=83fb7750db0601dca735868b8fb1da1318da2d2a1331a9a7da923cb891d26ea9)
  FetchContent_GetProperties(auth_cjson)
  if(NOT auth_cjson_POPULATED)
    FetchContent_Populate(auth_cjson)
  endif()
  set(CJSON_SOURCE_DIR "${auth_cjson_SOURCE_DIR}")
endif()
set(mbed_sources asn1parse asn1write base64 bignum bignum_core bignum_mod bignum_mod_raw
    constant_time ecp ecp_curves ecdsa sha256 platform_util platform)
foreach(name IN LISTS mbed_sources)
  list(APPEND sources "${MBEDTLS_SOURCE_DIR}/library/${name}.c")
endforeach()
add_library(test-crypto STATIC ${sources} "${CJSON_SOURCE_DIR}/cJSON.c")
target_include_directories(test-crypto PUBLIC "${MBEDTLS_SOURCE_DIR}/include"
  "${CJSON_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}" PRIVATE "${MBEDTLS_SOURCE_DIR}/library")
target_compile_definitions(test-crypto PUBLIC MBEDTLS_CONFIG_FILE="mbedtls_test_config.h")
add_executable(offline-auth-test offline_auth_test.cpp ../components/offline-auth/offline_auth.cpp)
target_include_directories(offline-auth-test PRIVATE ../components/offline-auth/include)
target_link_libraries(offline-auth-test PRIVATE test-crypto)
# Tests use assertions for crypto calls as well as expectations, including Release builds.
if(MSVC)
  target_compile_options(offline-auth-test PRIVATE /UNDEBUG /W4 /WX)
else()
  target_compile_options(offline-auth-test PRIVATE -UNDEBUG -Wall -Wextra -Werror)
endif()
add_test(NAME offline-auth COMMAND offline-auth-test
  "${CMAKE_CURRENT_SOURCE_DIR}/../../protocol/fixtures/offline-credential-v1.json")

set_tests_properties(offline-auth PROPERTIES LABELS "auth")
