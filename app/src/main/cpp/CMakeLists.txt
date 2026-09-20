cmake_minimum_required(VERSION 3.10)
project(loghook)

set(DOBBY_GENERATE_SHARED OFF)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(dobby)
set_target_properties(dobby_static PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(loghook SHARED loghook.cpp)
target_link_libraries(loghook dobby_static log)
target_compile_options(loghook PRIVATE -O2 -fvisibility=hidden)
# ★ 符号卫生 + 16KB 页对齐（审阅 2026-09 P2）：exclude-libs 阻止 dobby_static 符号
# 泄漏进 loghook.so 动态导出表；max-page-size 满足 Android 15+ 16KB 页设备要求
set_target_properties(loghook PROPERTIES LINK_FLAGS "-Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384")