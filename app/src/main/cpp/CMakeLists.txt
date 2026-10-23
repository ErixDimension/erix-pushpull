cmake_minimum_required(VERSION 3.22.1)
project("erixadb")

find_library(log-lib log)
find_library(libusb-lib libusb-1.0)

add_library(erixadb SHARED native-adb.cpp)
target_link_libraries(erixadb ${libusb-lib} ${log-lib})
