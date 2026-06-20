cmake_minimum_required(VERSION 3.13)
project(answer C)

# AnswerModule.cmake is found only through CMAKE_MODULE_PATH, which the plugin's moduleOverrides prepends; it sets
# ANSWER_VALUE. Without the override the include() aborts configure, so a successful build proves moduleOverrides
# reached cmake.
include(AnswerModule)

add_library(answer answer.c)
target_include_directories(answer PUBLIC include)
target_compile_definitions(answer PRIVATE SNX_ANSWER=${ANSWER_VALUE})

install(TARGETS answer ARCHIVE DESTINATION lib)
install(FILES include/answer.h DESTINATION include)
