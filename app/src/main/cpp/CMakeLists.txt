cmake_minimum_required(VERSION 3.22.1)

project("myapplication")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(myapplication SHARED
        native-lib.cpp
        qrcodegen.cpp)

target_link_libraries(myapplication
        android
        log
        jnigraphics)