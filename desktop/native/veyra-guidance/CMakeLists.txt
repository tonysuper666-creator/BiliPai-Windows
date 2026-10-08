cmake_minimum_required(VERSION 3.24)
project(bilipai_guidance_colors LANGUAGES CXX)
if(NOT WIN32 OR NOT MSVC OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "Windows x64 MSVC required for the compile-only color stage")
endif()
# This OBJECT target creates no new native runtime, NGX host or application selector.
# No Optical Flow SDK dependency is implied. NVOF integration remains blocked.
add_library(bilipai_guidance_colors OBJECT bilipai_guidance_colors.cpp)
set_property(TARGET bilipai_guidance_colors PROPERTY MSVC_RUNTIME_LIBRARY MultiThreaded)
target_compile_features(bilipai_guidance_colors PRIVATE cxx_std_20)
target_compile_definitions(bilipai_guidance_colors PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(bilipai_guidance_colors PRIVATE /W4 /WX /EHsc /utf-8 /permissive-)
