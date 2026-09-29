cmake_minimum_required(VERSION 3.16)
project(cryptand_rv32 CXX)

# ============================================================================
# Cryptand RV32 native kernel (C++17)
#
# 说明：本工程的**主构建路径是 build.ps1**（直接调 g++，Windows 上最省事）。
# 这里保留 CMakeLists 供其他平台 / CI 使用：
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build --config Release
# JNI 头文件位置：-DJAVA_HOME=<jdk 路径>
# ============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED JAVA_HOME AND DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME $ENV{JAVA_HOME})
endif()

add_library(cryptand_rv32 SHARED
    src/rv32_core.cpp
    src/sandbox.cpp
    src/jni_bridge.cpp
)

target_include_directories(cryptand_rv32 PRIVATE include)

if(JAVA_HOME)
    target_include_directories(cryptand_rv32 PRIVATE
        "${JAVA_HOME}/include"
        "${JAVA_HOME}/include/win32"
        "${JAVA_HOME}/include/linux"
        "${JAVA_HOME}/include/darwin"
    )
endif()

if(MSVC)
    target_compile_options(cryptand_rv32 PRIVATE /O2 /EHsc-)
else()
    target_compile_options(cryptand_rv32 PRIVATE -O2 -fno-exceptions -fno-rtti)
    # 沙箱用 std::mutex/condition_variable ⇒ 需要 winpthread，且必须**静态**链入
    # （动态依赖 libwinpthread-1.dll 时 MC 进程加载不到 ⇒ UnsatisfiedLinkError）。
    # 注意不能用 -static：它和 -shared 一起会让 ld 去找 WinMain。
    target_link_options(cryptand_rv32 PRIVATE -static-libgcc -static-libstdc++)
    target_link_libraries(cryptand_rv32 PRIVATE -Wl,-Bstatic winpthread -Wl,-Bdynamic)
endif()

set_target_properties(cryptand_rv32 PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
