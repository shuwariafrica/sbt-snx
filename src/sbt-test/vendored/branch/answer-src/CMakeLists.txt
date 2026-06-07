cmake_minimum_required(VERSION 3.13)
project(answer C)

add_library(answer STATIC answer.c)

install(TARGETS answer ARCHIVE DESTINATION lib)
