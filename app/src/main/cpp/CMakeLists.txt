cmake_minimum_required(VERSION 3.22.1)

project(zeus CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The engine is plain C++ with no Android dependencies; see ../tools/hosttest
# for the host build of the same sources.
add_subdirectory(whisper.cpp whisper_build EXCLUDE_FROM_ALL)

add_library(zeus SHARED
    zeus_jni.cpp
    zeus_transcriber.cpp
)

target_include_directories(zeus PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(zeus
    PRIVATE
        whisper
        android
        log
)