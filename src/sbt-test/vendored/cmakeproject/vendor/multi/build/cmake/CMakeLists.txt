cmake_minimum_required(VERSION 3.13)
project(multi C)

include(GNUInstallDirs)

# The project sits two levels below the vendored source root and builds sources outside its own directory, as zstd's
# build/cmake does. The archives reference each other top -> mid -> base, and base calls back into a second member of
# zz_top, so their sorted order (aa, mm, zz) and their dependency order (zz, mm, aa) both fail on GNU ld ungrouped.
set(MULTI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../..")
add_library(aa_base STATIC "${MULTI_ROOT}/src/base.c")
add_library(mm_mid STATIC "${MULTI_ROOT}/src/mid.c")
add_library(zz_top STATIC "${MULTI_ROOT}/src/top.c" "${MULTI_ROOT}/src/cycle.c")
target_include_directories(zz_top PRIVATE "${MULTI_ROOT}/include")

install(TARGETS aa_base mm_mid zz_top ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(FILES "${MULTI_ROOT}/include/multi.h" DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}")
# An absolute destination fixed at configure time, as brotli's man pages are: it lies under the staging prefix only
# when the prefix is set at configure.
install(FILES "${MULTI_ROOT}/man/multi.3" DESTINATION "${CMAKE_INSTALL_FULL_MANDIR}/man3")
