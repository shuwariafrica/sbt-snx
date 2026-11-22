cmake_minimum_required(VERSION 3.13)
project(clones C)

add_library(clones STATIC clones.c)

install(TARGETS clones ARCHIVE DESTINATION lib)
