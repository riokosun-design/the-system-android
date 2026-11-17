cmake_minimum_required(VERSION 3.22.1)
project(rtmpose_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NCNN Android SDK (plain CPU build, no Vulkan) is vendored at BUILD TIME by CI:
#   app/src/main/cpp/ncnn-sdk/<abi>/{include,lib}/  — see .github/workflows/android.yml
# Kept out of git on purpose: 100MB of prebuilt binaries is not source code.
set(NCNN_SDK ${CMAKE_CURRENT_SOURCE_DIR}/ncnn-sdk)

add_library(rtmpose_jni SHARED rtmpose_jni.cpp)

target_include_directories(rtmpose_jni PRIVATE
    ${NCNN_SDK}/${ANDROID_ABI}/include
    ${NCNN_SDK}/${ANDROID_ABI}/include/ncnn)

add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${NCNN_SDK}/${ANDROID_ABI}/lib/libncnn.a)

find_library(lib_log log)
find_library(lib_android android)

# The prebuilt libncnn.a carries a handful of OpenMP symbol references; satisfy
# them statically so the APK never depends on a system libomp.
target_link_options(rtmpose_jni PRIVATE -fopenmp -static-openmp)

target_link_libraries(rtmpose_jni ncnn ${lib_android} ${lib_log})
