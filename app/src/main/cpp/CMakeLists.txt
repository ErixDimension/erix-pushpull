cmake_minimum_required(VERSION 3.22.1)
project("erixadb")

find_library(log-lib log)

# Import libusb dari jniLibs (path eksplisit)
set(LIBUSB_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/arm64-v8a/libusb-1.0.so)
message(STATUS "Looking for libusb at: ${LIBUSB_PATH}")

if(NOT EXISTS ${LIBUSB_PATH})
    message(FATAL_ERROR "libusb-1.0.so tidak ditemukan di ${LIBUSB_PATH}")
endif()

add_library(libusb SHARED IMPORTED)
set_target_properties(libusb PROPERTIES IMPORTED_LOCATION ${LIBUSB_PATH})

add_library(erixadb SHARED native-adb.cpp)
target_link_libraries(erixadb libusb ${log-lib})
