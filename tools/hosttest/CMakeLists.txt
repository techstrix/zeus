# Host build of the shipping engine, so the decode path can be verified without
# an Android device or emulator. Builds ../app/src/main/cpp/zeus_transcriber.cpp
# unmodified against the same vendored whisper.cpp the APK uses.
cmake_minimum_required(VERSION 3.16)

project(zeus_hosttest CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/whisper.cpp)
set(ZEUS_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)

if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp not vendored at ${WHISPER_DIR}. Run ./gradlew fetchWhisperCpp")
endif()

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build EXCLUDE_FROM_ALL)

add_executable(hosttest
    main.cpp
    ${ZEUS_CPP}/zeus_transcriber.cpp
)

target_include_directories(hosttest PRIVATE ${ZEUS_CPP} ${WHISPER_DIR}/include)
target_link_libraries(hosttest PRIVATE whisper)

# Separate binary: measures how decode cost scales with audio length, which is
# what decides the partial-result strategy.
add_executable(scaling
    scaling.cpp
    ${ZEUS_CPP}/zeus_transcriber.cpp
)
target_include_directories(scaling PRIVATE ${ZEUS_CPP} ${WHISPER_DIR}/include)
target_link_libraries(scaling PRIVATE whisper)