cmake_minimum_required(VERSION 3.13)
project(answer C)

add_library(answer STATIC answer.c)
target_include_directories(answer PUBLIC include)

install(TARGETS answer ARCHIVE DESTINATION lib)
install(FILES include/answer.h DESTINATION include)
