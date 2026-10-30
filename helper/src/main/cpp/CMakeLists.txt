cmake_minimum_required(VERSION 3.22.1)
project(kmnative C)

add_library(kmnative SHARED kmnative.c)
target_compile_options(kmnative PRIVATE -Wall -Wextra -Werror=implicit-function-declaration -O2)
target_link_options(kmnative PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(kmnative log)
