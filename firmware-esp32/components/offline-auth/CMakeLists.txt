idf_component_register(SRCS "offline_auth.cpp" "node_time_verify.cpp" INCLUDE_DIRS "include" REQUIRES mbedtls espressif__cjson firmware-core)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_20)
