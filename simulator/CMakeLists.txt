add_library(firmware-simulator
  src/sim_clock.cpp
  src/sim_rfid.cpp
  src/sim_stores.cpp
  src/scenario.cpp
  src/scenario_runner.cpp
)
target_include_directories(firmware-simulator PUBLIC include)
target_link_libraries(firmware-simulator PUBLIC firmware-core)
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

  file(GLOB SCENARIO_FILES "${CMAKE_CURRENT_SOURCE_DIR}/scenarios/*.scenario")
  foreach(file ${SCENARIO_FILES})
    get_filename_component(name ${file} NAME_WE)
    add_test(NAME scenario-${name} COMMAND scenario-runner ${file})
  endforeach()
endif()