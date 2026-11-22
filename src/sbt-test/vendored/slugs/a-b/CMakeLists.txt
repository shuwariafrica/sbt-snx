cmake_minimum_required(VERSION 3.13)
project(dash C)

add_library(dash STATIC dash.c)

install(TARGETS dash ARCHIVE DESTINATION lib)
