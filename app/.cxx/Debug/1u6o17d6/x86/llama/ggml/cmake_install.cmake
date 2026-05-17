# Install script for directory: /root/.hermes/projects/moreader-android/llama.cpp/ggml

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "/usr/local")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Debug")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "1")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set default install directory permissions.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "/opt/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump")
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for the subdirectory.
  include("/root/.hermes/projects/moreader-android/app/.cxx/Debug/1u6o17d6/x86/llama/ggml/src/cmake_install.cmake")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/root/.hermes/projects/moreader-android/app/.cxx/Debug/1u6o17d6/x86/llama/ggml/src/libggml.a")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE FILE FILES
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-cpu.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-alloc.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-backend.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-blas.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-cann.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-cpp.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-cuda.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-kompute.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-opt.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-metal.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-rpc.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-sycl.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/ggml-vulkan.h"
    "/root/.hermes/projects/moreader-android/llama.cpp/ggml/include/gguf.h"
    )
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/root/.hermes/projects/moreader-android/app/.cxx/Debug/1u6o17d6/x86/llama/ggml/src/libggml-base.a")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/ggml" TYPE FILE FILES
    "/root/.hermes/projects/moreader-android/app/.cxx/Debug/1u6o17d6/x86/llama/ggml/ggml-config.cmake"
    "/root/.hermes/projects/moreader-android/app/.cxx/Debug/1u6o17d6/x86/llama/ggml/ggml-version.cmake"
    )
endif()

