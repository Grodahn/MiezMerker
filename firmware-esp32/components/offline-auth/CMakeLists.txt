idf_component_register(SRCS "offline_auth.cpp" INCLUDE_DIRS "include" REQUIRES mbedtls json)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_20)
