cmake_minimum_required(VERSION 3.22.1)
project("erixadb")
include_directories(${CMAKE_SOURCE_DIR})
find_library(log-lib log)

set(LIBUSB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libusb-1.0.so")
if(NOT EXISTS "${LIBUSB_PATH}")
    message(FATAL_ERROR "libusb-1.0.so NOT FOUND")
endif()

add_library(libusb SHARED IMPORTED)
set_target_properties(libusb PROPERTIES IMPORTED_LOCATION "${LIBUSB_PATH}")
add_library(erixadb SHARED native-adb.cpp)
target_link_libraries(erixadb libusb ${log-lib})
