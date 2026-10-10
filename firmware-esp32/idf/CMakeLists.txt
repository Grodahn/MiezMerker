cmake_minimum_required(VERSION 3.22)
set(IDF_TARGET esp32c3)
set(COMPONENTS main)
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/../components/ble-sync"
    "${CMAKE_CURRENT_LIST_DIR}/../components/offline-auth")
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
execute_process(COMMAND git describe --always --dirty WORKING_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}"
    OUTPUT_VARIABLE MM_REV OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
if(NOT MM_REV)
    set(MM_REV "source-archive")
endif()
set(PROJECT_VER "mm108-${MM_REV}")
project(miezmerker-node)
