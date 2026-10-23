cmake_minimum_required(VERSION 3.22.1)
project("erixadb")

find_library(log-lib log)

# Import libusb dari folder assets
add_library(libusb SHARED IMPORTED)
set_target_properties(libusb PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../assets/lib/libusb-1.0.so)

add_library(erixadb SHARED native-adb.cpp)
target_link_libraries(erixadb libusb ${log-lib})
