cmake_minimum_required(VERSION 3.14)

project(CryptandNative VERSION 1.0.0 LANGUAGES C)

# Dependencies are in the PowerGrid project (already cloned)
set(PG_OPENBLAS_DIR "E:/Projects/MCMDK/1/PowerGrid/native/OpenBLAS")
set(PG_SUPERLU_DIR "E:/Projects/MCMDK/1/PowerGrid/native/superlu")

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SOURCES
    src/powergridNative/c/sparse.c
    src/powergridNative/c/solver.c
    src/powergridNative/c/mna.c
)

set(SOURCES_SAFE_EXEC
    src/powergridNative/c/exception_handler.c
    src/powergridNative/c/verify.c
)

add_library(powergridNative SHARED ${SOURCES} ${SOURCES_SAFE_EXEC})
set_target_properties(powergridNative PROPERTIES OUTPUT_NAME "powergridNative7")

target_include_directories(powergridNative PUBLIC src/powergridNative/include ${JVM_INCLUDE})

if(${CMAKE_BUILD_TYPE} STREQUAL "Release")
  target_compile_options(powergridNative PRIVATE "-mavx2")
  set_source_files_properties(${SOURCES_SAFE_EXEC} PROPERTIES COMPILE_FLAGS "-mno-avx2")
endif()

set_target_properties(powergridNative PROPERTIES CXX_STANDARD 20)

# OpenBLAS - use pre-cloned copy from PowerGrid project
set(BUILD_SHARED_LIBS OFF)
set(BUILD_STATIC_LIBS ON)
set(BUILD_WITHOUT_LAPACK ON)
set(BUILD_TESTING OFF)
set(BUILD_SINGLE true)
set(BUILD_DOUBLE true)
set(BUILD_COMPLEX false)
set(BUILD_COMPLEX16 false)
set(BUILD_MATGEN false)
set(USE_THREAD false)

set(DYNAMIC_ARCH false)
set(NOFORTRAN true)

add_subdirectory(${PG_OPENBLAS_DIR} OpenBLAS)

set(TPL_BLAS_LIBRARIES "openblas")
set(enable_internal_blaslib OFF)
set(enable_single OFF)
set(enable_double ON)
set(enable_complex OFF)
set(enable_complex16 OFF)
set(enable_matlabmex OFF)
set(enable_doc OFF)
set(enable_examples OFF)
set(enable_fortran OFF)
set(enable_tests OFF)
add_subdirectory(${PG_SUPERLU_DIR} superlu)

if(${CMAKE_BUILD_TYPE} STREQUAL "Release")
  target_compile_options(superlu PRIVATE "-mavx2")
endif()

target_link_libraries(powergridNative PUBLIC superlu openblas_static)
