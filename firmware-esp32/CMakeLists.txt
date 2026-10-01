# Build the composition boundary on a host without installing an ESP32 SDK.
# An ESP-IDF board application will provide these ports in the firmware ticket.
add_library(firmware-esp32-scaffold STATIC src/composition.cpp)
target_link_libraries(firmware-esp32-scaffold PUBLIC firmware-core)
target_include_directories(firmware-esp32-scaffold PUBLIC include)
