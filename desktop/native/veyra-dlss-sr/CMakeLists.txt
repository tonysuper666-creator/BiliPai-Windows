cmake_minimum_required(VERSION 3.25)
project(BiliPaiDlssSharedCoreEntry LANGUAGES CXX)
option(BILIDLSS_COMPILE_ONLY "Compile the actual shared v1/v2 core without SDK link or execution" ON)
# Never construct another NGX host or a separate DLSS DLL.
if(NOT TARGET bilipai_veyra_core)
  set(BILIVEYRA_COMPILE_ONLY "${BILIDLSS_COMPILE_ONLY}" CACHE BOOL "Compile the shared core only" FORCE)
  add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../veyra-core" "${CMAKE_CURRENT_BINARY_DIR}/shared-core")
endif()
