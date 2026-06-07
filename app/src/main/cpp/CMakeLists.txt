cmake_minimum_required(VERSION 3.22.1)
project("llama-local-ai")

# CPU ONLY — no Vulkan
option(BUILD_SHARED_LIBS "Build shared libraries" OFF)
option(LLAMA_BUILD_COMMON "Build common library" ON)
option(LLAMA_BUILD_EXAMPLES "Build examples" OFF)
option(LLAMA_BUILD_TESTS "Build tests" OFF)
option(LLAMA_BUILD_SERVER "Build server" OFF)
option(LLAMA_CURL "Build with curl support" OFF)
option(GGML_LLAMAFILE "Build with llamafile support" OFF)
option(GGML_OPENMP "Build with OpenMP support" OFF)
option(GGML_NATIVE "Build with native optimizations" OFF)
option(GGML_VULKAN "Enable Vulkan backend" ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)

# JNI wrapper
add_library(llama-local-ai SHARED llama-local-ai.cpp)

target_include_directories(llama-local-ai PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llama-local-ai
    PRIVATE llama common log android
)

set_target_properties(llama-local-ai PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
