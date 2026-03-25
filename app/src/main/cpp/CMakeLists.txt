cmake_minimum_required(VERSION 3.10.2)
project("FeedWatch")

# Add your JNI wrapper
add_library(ndi-wrapper SHARED
    ndi.cpp
    ndi-finder.cpp
    ndi-source.cpp
    ndi-video-frame.cpp
    ndi-audio-frame.cpp
    ndi-audio-utils.cpp
    ndi-receiver.cpp
    ndi-frame-sync.cpp
)

# Tell CMake where to find headers (NDI SDK)
include_directories(${CMAKE_SOURCE_DIR}/include)

# Tell CMake to import libndi.so
add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES
    IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so
)

# Find Android logging lib
find_library(log-lib log)

# Link NDI + logging
target_link_libraries(ndi-wrapper
    ndi
    ${log-lib}
)
