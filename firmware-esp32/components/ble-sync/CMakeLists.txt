idf_component_register(SRCS "ble_gatt.cpp" "gatt_endpoint.cpp"
                       INCLUDE_DIRS "include"
                       REQUIRES firmware-core)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_20)
