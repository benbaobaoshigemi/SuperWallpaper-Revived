cmake_minimum_required(VERSION 3.22.1)

project(saturnstyle LANGUAGES CXX)

add_library(saturnstyle SHARED saturn_style.cpp)

target_compile_options(saturnstyle PRIVATE
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden)

target_link_libraries(saturnstyle PRIVATE log dl)
