cmake_minimum_required(VERSION 3.22.1)
project(halowhisper)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp")
if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp nije pronađen. Pokreni scripts/setup.sh ili CI workflow.")
endif()

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_DIR}" whisper-build EXCLUDE_FROM_ALL)

add_library(halowhisper SHARED whisper_jni.cpp)
target_include_directories(halowhisper PRIVATE "${WHISPER_DIR}/include")
target_link_libraries(halowhisper whisper android log)
target_compile_options(halowhisper PRIVATE -O3 -DNDEBUG)
