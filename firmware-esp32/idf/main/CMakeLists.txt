idf_component_register(SRCS "app_main.cpp" "board.cpp" "nimble_peripheral.cpp"
    "../../src/persistent_state.cpp" "../../src/composition.cpp" INCLUDE_DIRS "." "../../include"
    REQUIRES firmware-core ble-sync offline-auth bt nvs_flash esp_timer esp_driver_gpio
        esp_driver_usb_serial_jtag bootloader_support mbedtls esp_app_format)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_20)
if(CONFIG_MM_DEV_SYNTHETIC_INPUT)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/../../../protocol/fixtures/offline-credential-v1.json" MM_FIXTURE)
    string(JSON MM_ISSUER_X GET "${MM_FIXTURE}" issuer_public_key x)
    string(JSON MM_ISSUER_Y GET "${MM_FIXTURE}" issuer_public_key y)
    string(JSON MM_ORG GET "${MM_FIXTURE}" organization_id)
    string(JSON MM_CREDENTIAL GET "${MM_FIXTURE}" credential_jwt)
    string(JSON MM_PROOF GET "${MM_FIXTURE}" challenge_signature_b64u)
    string(JSON MM_RECEIPT GET "${MM_FIXTURE}" claim_receipt_jwt)
    configure_file(auth_vectors.hpp.in "${CMAKE_CURRENT_BINARY_DIR}/auth_vectors.hpp" @ONLY)
    target_include_directories(${COMPONENT_LIB} PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
    target_sources(${COMPONENT_LIB} PRIVATE crypto_selftest.cpp)
endif()
