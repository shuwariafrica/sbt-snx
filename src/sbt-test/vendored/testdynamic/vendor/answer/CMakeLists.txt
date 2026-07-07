cmake_minimum_required(VERSION 3.13)
project(answer C)

# A plain library: the plugin sets BUILD_SHARED_LIBS from the resolved per-library linkage, so `.linkage(Dynamic)`
# builds this shared (libanswer.so). The install rules cover every component - LIBRARY (the .so/.dylib), ARCHIVE (a
# static .a or a Windows import .lib), and RUNTIME (a Windows .dll).
add_library(answer answer.c)
target_include_directories(answer PUBLIC include)

install(TARGETS answer
  LIBRARY DESTINATION lib
  ARCHIVE DESTINATION lib
  RUNTIME DESTINATION bin)
install(FILES include/answer.h DESTINATION include)
