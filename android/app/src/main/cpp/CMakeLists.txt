# دفتري · cpp/CMakeLists.txt
# بناء whisper.cpp (MIT، نسخة 1.9.5 مقتطعة: المعالج فقط) ومكتبة JNI «daftari_whisper» لنواتي arm64-v8a و armeabi-v7a.
# Builds the vendored whisper.cpp v1.9.5 (CPU backend only; other ggml backends removed from the copy) as static libs and
# links them into libdaftari_whisper.so. Release -O3; NEON is on by default for arm64 and set by ggml's own flags for armv7.
cmake_minimum_required(VERSION 3.22.1)
project(daftari_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

if (NOT CMAKE_BUILD_TYPE OR CMAKE_BUILD_TYPE STREQUAL "Debug")
    # a debug-built whisper is ~20× slower; the app always ships the optimised one
    set(CMAKE_BUILD_TYPE Release)
endif()
string(APPEND CMAKE_C_FLAGS_RELEASE " -O3")
string(APPEND CMAKE_CXX_FLAGS_RELEASE " -O3")

add_subdirectory(whisper.cpp)

add_library(daftari_whisper SHARED whisper_jni.cpp daftari_whisper.cpp)
target_link_libraries(daftari_whisper PRIVATE whisper log)
target_link_options(daftari_whisper PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
