cmake_minimum_required(VERSION 3.13)
project(nested C)

add_library(nested STATIC nested.c)

install(TARGETS nested ARCHIVE DESTINATION lib)
