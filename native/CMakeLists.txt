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
    src/powergridNative/c/jni_sparse.c
    src/powergridNative/c/jni_dense.c
)

set(SOURCES_SAFE_EXEC
    src/powergridNative/c/exception_handler.c
    src/powergridNative/c/verify.c
)

add_library(powergridNative SHARED ${SOURCES} ${SOURCES_SAFE_EXEC})
set_target_properties(powergridNative PROPERTIES OUTPUT_NAME "powergridNative7")

target_include_directories(powergridNative PUBLIC src/powergridNative/include ${JVM_INCLUDE}
    ${PG_OPENBLAS_DIR}/lapack-netlib/LAPACKE/include)

if(${CMAKE_BUILD_TYPE} STREQUAL "Release")
  target_compile_options(powergridNative PRIVATE "-mavx2")
  set_source_files_properties(${SOURCES_SAFE_EXEC} PROPERTIES COMPILE_FLAGS "-mno-avx2")
endif()

set_target_properties(powergridNative PROPERTIES CXX_STANDARD 20)

# OpenBLAS - use pre-cloned copy from PowerGrid project
set(BUILD_SHARED_LIBS OFF)
set(BUILD_STATIC_LIBS ON)
set(BUILD_WITHOUT_LAPACK OFF)   # 启用 OpenBLAS LAPACK（稠密 dgesv/zgesv/sgesv/cgesv）
set(BUILD_TESTING OFF)
set(BUILD_SINGLE true)
set(BUILD_DOUBLE true)
set(BUILD_COMPLEX true)     # float complex BLAS（cgesv / SuperLU SCZ 需要）
set(BUILD_COMPLEX16 true)   # double complex BLAS（ZGEMM 等）
set(BUILD_MATGEN false)
set(USE_THREAD false)       # 单线程 BLAS：多线程交给网络间并行（solveAll），避免线程过载

# 运行时分派：编入多微架构内核（SSE2..AVX512），运行时 cpuid 自动选最优
# （注意：会显著增加 OpenBLAS 静态库/DLL 体积与编译时间）
set(DYNAMIC_ARCH true)
set(NOFORTRAN true)

add_subdirectory(${PG_OPENBLAS_DIR} OpenBLAS)

set(TPL_BLAS_LIBRARIES "openblas")
set(enable_internal_blaslib OFF)
set(enable_single ON)       # SuperLU S/SCZ（float 单精度，配置 floatEnabled 时用）
set(enable_double ON)       # SuperLU D/Z（double）
set(enable_complex ON)      # SuperLU C（float complex）
set(enable_complex16 ON)    # SuperLU Z 类型（zgssv/zgstrf，double complex）
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
