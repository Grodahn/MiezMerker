# Rechecked on every incremental build; unchanged contents preserve the header's
# timestamp, so an idle build does not recompile the application.
find_package(Git QUIET)
set(MM_BUILD_ID "unknown")
if(GIT_FOUND)
    execute_process(COMMAND "${GIT_EXECUTABLE}" rev-parse --short=7 HEAD
        WORKING_DIRECTORY "${MM_SOURCE_DIR}" RESULT_VARIABLE revision_result
        OUTPUT_VARIABLE revision OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    execute_process(COMMAND "${GIT_EXECUTABLE}" status --porcelain --untracked-files=no
        WORKING_DIRECTORY "${MM_SOURCE_DIR}" RESULT_VARIABLE status_result
        OUTPUT_VARIABLE changes OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    if(revision_result EQUAL 0 AND status_result EQUAL 0 AND revision MATCHES "^[0-9a-f]+$")
        set(MM_BUILD_ID "${revision}")
        if(NOT changes STREQUAL "")
            string(APPEND MM_BUILD_ID "-dirty")
        endif()
    endif()
endif()
configure_file("${CMAKE_CURRENT_LIST_DIR}/build_id.hpp.in" "${MM_OUTPUT_FILE}" @ONLY)
