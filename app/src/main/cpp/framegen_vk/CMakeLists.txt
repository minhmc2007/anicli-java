cmake_minimum_required(VERSION 3.22.1)
project(framegen_vk CXX)

add_library(framegen_vk SHARED framegen_vk.cpp)
target_compile_features(framegen_vk PRIVATE cxx_std_17)
target_compile_options(framegen_vk PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(framegen_vk android log vulkan EGL GLESv3)
