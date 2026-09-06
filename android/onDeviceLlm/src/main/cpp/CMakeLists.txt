# Phase 1 stub: prove the Gradle/NDK/CMake/JNI pipeline round-trips end to
# end, with no llama.cpp dependency yet. Phase 2 replaces this file's body
# with a FetchContent-based llama.cpp build + the real JNI bridge -- see
# ON_DEVICE_LLM_PLAN.md.
cmake_minimum_required(VERSION 3.22.1)
project("ondevicellm")

add_library(${CMAKE_PROJECT_NAME} SHARED
    smoke.cpp
)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${log-lib}
)
