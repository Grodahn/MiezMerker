add_library(firmware-core
  src/core.cpp
  src/memory_stores.cpp
  src/protocol_view.cpp
  src/types.cpp
)
target_include_directories(firmware-core PUBLIC include)
if(MSVC)
  target_compile_options(firmware-core PRIVATE /W4 /WX)
else()
  target_compile_options(firmware-core PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()
if(BUILD_TESTING)
  add_executable(core-test tests/core_test.cpp)
  target_link_libraries(core-test PRIVATE firmware-core)
  add_test(NAME firmware-core COMMAND core-test)

  add_executable(observation-test tests/observation_test.cpp)
  target_link_libraries(observation-test PRIVATE firmware-core)
  if(MSVC)
    target_compile_options(observation-test PRIVATE /W4 /WX)
  else()
    target_compile_options(observation-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
  endif()
  add_test(NAME firmware-observations COMMAND observation-test)
endif()
