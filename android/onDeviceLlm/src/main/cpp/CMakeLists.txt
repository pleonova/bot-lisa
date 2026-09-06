# Adapted from ggml-org/llama.cpp examples/llama.android (tag v0.4.0),
# lib/src/main/cpp/CMakeLists.txt. Two real changes from upstream:
#   1. FetchContent instead of a relative add_subdirectory -- upstream's
#      example lives *inside* a llama.cpp checkout; we don't vendor the
#      whole llama.cpp source tree into this repo, we pull it at configure
#      time instead (same spirit as llm_lab's "download the GGUF, don't
#      commit it" pattern).
#   2. Library/project name "ondevicellm" (matches this module's Phase 1
#      stub) instead of upstream's "ai-chat".
# See ON_DEVICE_LLM_PLAN.md Phase 2 for the rest of the adaptation notes
# (JNI symbol renames live in llama_bridge.cpp, not here).
# llama.cpp itself only requires CMake 3.14-3.28 (its own CMakeLists.txt);
# 3.22.1 here matches what's already proven to auto-install via the Android
# SDK's package repository (see Phase 1) -- upstream's own example pins
# 3.31.6, but that's their toolchain choice, not something llama.cpp needs.
cmake_minimum_required(VERSION 3.22.1)

project("ondevicellm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# On-device LLM library (llama.cpp JNI bridge)
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# Pinned to the exact llama.cpp release the JNI bridge in llama_bridge.cpp
# was adapted from -- bump both together, never track a moving branch.
include(FetchContent)
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        v0.4.0
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_bridge.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${llama_cpp_SOURCE_DIR}
        ${llama_cpp_SOURCE_DIR}/common
        ${llama_cpp_SOURCE_DIR}/include
        ${llama_cpp_SOURCE_DIR}/ggml/include
        ${llama_cpp_SOURCE_DIR}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
