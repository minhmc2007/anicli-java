# framegen_ncnn - FastFlowNet flow engine for frame generation.
# Links the ncnn android-vulkan static release downloaded by Gradle
# (-DNCNN_DIR=<build>/ncnn/current).
cmake_minimum_required(VERSION 3.18)
project(framegen_ncnn LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT NCNN_DIR)
    message(FATAL_ERROR "NCNN_DIR must point at the extracted ncnn android-vulkan release")
endif()

set(ncnn_DIR "${NCNN_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(framegen_ncnn SHARED framegen_ncnn.cpp)
target_compile_options(framegen_ncnn PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(framegen_ncnn ncnn android log)
