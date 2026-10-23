cmake_minimum_required(VERSION 3.22.1)
project("erixadb")

# Tambah log library bawaan Android
find_library(log-lib log)

add_library(erixadb SHARED native-adb.cpp)

target_link_libraries(erixadb ${log-lib})
