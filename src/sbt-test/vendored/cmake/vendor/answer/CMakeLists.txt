cmake_minimum_required(VERSION 3.13)
project(answer C)

# Enabled only when the plugin's per-platform configure flags pass -DSNX_VIA_FLAG=ON.
option(SNX_VIA_FLAG "compile-define answer.c with SNX_VIA_FLAG" OFF)

# Plain library (no STATIC): a static archive is produced only because the plugin passes -DBUILD_SHARED_LIBS=OFF.
add_library(answer answer.c)
target_include_directories(answer PUBLIC include)
if(SNX_VIA_FLAG)
  target_compile_definitions(answer PRIVATE SNX_VIA_FLAG)
endif()

install(TARGETS answer ARCHIVE DESTINATION lib)
install(FILES include/answer.h DESTINATION include)
