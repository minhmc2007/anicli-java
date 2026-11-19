cmake_minimum_required(VERSION 3.22.1)

project(aniclijava_framegen LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT NCNN_DIR)
    message(FATAL_ERROR "NCNN_DIR must point at the extracted ncnn android-vulkan release")
endif()

set(ncnn_DIR "${NCNN_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

set(RIFE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rife")

# ncnn compiles the GLSL compute shaders on the device; embed them as byte arrays.
set(RIFE_SHADERS
        rife_preproc
        rife_postproc
        rife_preproc_tta
        rife_postproc_tta
        rife_flow_tta_avg
        rife_v2_flow_tta_avg
        rife_v4_flow_tta_avg
        rife_flow_tta_temporal_avg
        rife_v2_flow_tta_temporal_avg
        rife_v4_flow_tta_temporal_avg
        rife_out_tta_temporal_avg
        rife_v4_timestep
        rife_v4_timestep_tta
        warp
        warp_pack4)

set(SHADER_HEADERS "")
foreach(shader ${RIFE_SHADERS})
    set(shader_src "${RIFE_DIR}/${shader}.comp")
    set(shader_header "${CMAKE_CURRENT_BINARY_DIR}/${shader}.comp.hex.h")
    add_custom_command(
            OUTPUT "${shader_header}"
            COMMAND ${CMAKE_COMMAND}
            -DSHADER_SRC=${shader_src}
            -DSHADER_COMP_HEADER=${shader_header}
            -P "${RIFE_DIR}/generate_shader_comp_header.cmake"
            DEPENDS "${shader_src}"
            VERBATIM)
    list(APPEND SHADER_HEADERS "${shader_header}")
endforeach()

add_custom_target(framegen_shaders DEPENDS ${SHADER_HEADERS})

add_library(framegen SHARED
        framegen_jni.cpp
        ${RIFE_DIR}/rife.cpp
        ${RIFE_DIR}/warp.cpp)

add_dependencies(framegen framegen_shaders)
target_include_directories(framegen PRIVATE "${RIFE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_options(framegen PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(framegen ncnn android log)
