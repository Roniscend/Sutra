cmake_minimum_required(VERSION 3.22)
project(itantra_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/whisper.cpp)

if (NOT EXISTS ${WHISPER_DIR}/src/whisper.cpp)
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_DIR} — run ./tools/fetch-deps.sh, or: git clone --depth 1 https://github.com/ggml-org/whisper.cpp vendor/whisper.cpp")
endif ()

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(itantra_whisper SHARED
        ${WHISPER_DIR}/src/whisper.cpp
        ${WHISPER_DIR}/examples/grammar-parser.cpp
        itantra_whisper.c
        itantra_grammar.cpp
)

target_include_directories(itantra_whisper PRIVATE
        ${WHISPER_DIR}
        ${WHISPER_DIR}/examples
        ${WHISPER_DIR}/src
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include
        ${WHISPER_DIR}/ggml/src
)

target_compile_definitions(itantra_whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(itantra_whisper PRIVATE WHISPER_VERSION="itantra")

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(itantra_whisper PRIVATE -march=armv8.2-a+fp16)
    target_compile_options(ggml-cpu PRIVATE -march=armv8.2-a+fp16)
endif ()

foreach (ggml_target ggml ggml-base ggml-cpu)
    if (TARGET ${ggml_target})
        target_compile_options(${ggml_target} PRIVATE -O3)
    endif ()
endforeach ()

target_compile_options(itantra_whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
target_compile_options(itantra_whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(itantra_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
target_link_libraries(itantra_whisper ${LOG_LIB} ${ANDROID_LIB} ggml)
