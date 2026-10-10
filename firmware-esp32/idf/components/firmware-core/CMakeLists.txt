set(CORE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../firmware-core")
idf_component_register(SRCS
    "${CORE_ROOT}/src/types.cpp" "${CORE_ROOT}/src/core.cpp"
    "${CORE_ROOT}/src/node_identity.cpp" "${CORE_ROOT}/src/ble_codec.cpp"
    "${CORE_ROOT}/src/ble_sync.cpp"
    INCLUDE_DIRS "${CORE_ROOT}/include")
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_20)
