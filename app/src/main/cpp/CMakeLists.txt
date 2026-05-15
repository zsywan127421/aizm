
cmake_minimum_required(VERSION 3.22.1)
project("aiaimassist")

add_library(
    native-lib
    SHARED
    native-lib.cpp
)

target_link_libraries(
    native-lib
    log
)

