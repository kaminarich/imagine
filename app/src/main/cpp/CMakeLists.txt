cmake_minimum_required(VERSION 3.18.1)
project("imagine" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_BUILD_TYPE Release)

# ncnn prebuilt location (downloaded by CI): app/ncnn-android/
# layout: <abi>/lib/libncnn.so + <abi>/include/ncnn/...
set(NCNN_ROOT ${CMAKE_SOURCE_DIR}/../../../ncnn-android)

find_package(Vulkan REQUIRED)

# ---- compile compute shaders to SPIR-V hex headers (like upstream) ----
find_program(GLSLANGVALIDATOR_EXECUTABLE NAMES glslangValidator)
if(NOT GLSLANGVALIDATOR_EXECUTABLE)
    message(FATAL_ERROR "glslangValidator not found; install glslang-tools")
endif()

macro(compile_shader SHADER_SRC)
    set(SHADER_SRC_FULLPATH ${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC})
    get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)
    set(SHADER_SPV_HEX_FILE ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.spv.hex.h)
    add_custom_command(
        OUTPUT ${SHADER_SPV_HEX_FILE}
        COMMAND ${GLSLANGVALIDATOR_EXECUTABLE}
        ARGS -V -s -x -o ${SHADER_SPV_HEX_FILE} ${SHADER_SRC_FULLPATH}
        DEPENDS ${SHADER_SRC_FULLPATH}
        COMMENT "Building SPIR-V module ${SHADER_SRC_NAME_WE}.spv"
        VERBATIM
    )
    set_source_files_properties(${SHADER_SPV_HEX_FILE} PROPERTIES GENERATED TRUE)
    list(APPEND SHADER_SPV_HEX_FILES ${SHADER_SPV_HEX_FILE})

    # fp16 storage
    set(SHADER_fp16s_SRC_NAME_WE "${SHADER_SRC_NAME_WE}_fp16s")
    set(SHADER_fp16s_SPV_HEX_FILE ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_fp16s_SRC_NAME_WE}.spv.hex.h)
    add_custom_command(
        OUTPUT ${SHADER_fp16s_SPV_HEX_FILE}
        COMMAND ${GLSLANGVALIDATOR_EXECUTABLE}
        ARGS -DNCNN_fp16_storage=1 -V -s -x -o ${SHADER_fp16s_SPV_HEX_FILE} ${SHADER_SRC_FULLPATH}
        DEPENDS ${SHADER_SRC_FULLPATH}
        COMMENT "Building SPIR-V module ${SHADER_fp16s_SRC_NAME_WE}.spv"
        VERBATIM
    )
    set_source_files_properties(${SHADER_fp16s_SPV_HEX_FILE} PROPERTIES GENERATED TRUE)
    list(APPEND SHADER_SPV_HEX_FILES ${SHADER_fp16s_SPV_HEX_FILE})

    # int8 storage
    set(SHADER_int8s_SRC_NAME_WE "${SHADER_SRC_NAME_WE}_int8s")
    set(SHADER_int8s_SPV_HEX_FILE ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_int8s_SRC_NAME_WE}.spv.hex.h)
    add_custom_command(
        OUTPUT ${SHADER_int8s_SPV_HEX_FILE}
        COMMAND ${GLSLANGVALIDATOR_EXECUTABLE}
        ARGS -DNCNN_fp16_storage=1 -DNCNN_int8_storage=1 -V -s -x -o ${SHADER_int8s_SPV_HEX_FILE} ${SHADER_SRC_FULLPATH}
        DEPENDS ${SHADER_SRC_FULLPATH}
        COMMENT "Building SPIR-V module ${SHADER_int8s_SRC_NAME_WE}.spv"
        VERBATIM
    )
    set_source_files_properties(${SHADER_int8s_SPV_HEX_FILE} PROPERTIES GENERATED TRUE)
    list(APPEND SHADER_SPV_HEX_FILES ${SHADER_int8s_SPV_HEX_FILE})
endmacro()

include_directories(${CMAKE_CURRENT_BINARY_DIR})

set(SHADER_SPV_HEX_FILES)
compile_shader(realesrgan_preproc.comp)
compile_shader(realesrgan_postproc.comp)
compile_shader(realesrgan_preproc_tta.comp)
compile_shader(realesrgan_postproc_tta.comp)

add_custom_target(generate-spirv DEPENDS ${SHADER_SPV_HEX_FILES})

# ---- main JNI library ----
add_library(engine_jni SHARED
    engine_jni.cpp
    realesrgan.cpp
)

add_dependencies(engine_jni generate-spirv)

# per-ABI ncnn shared library
set(NCNN_LIB ${NCNN_ROOT}/${ANDROID_ABI}/lib/libncnn.so)
if(NOT EXISTS ${NCNN_LIB})
    message(FATAL_ERROR "ncnn shared lib not found: ${NCNN_LIB}")
endif()

target_include_directories(engine_jni PRIVATE
    ${NCNN_ROOT}/${ANDROID_ABI}/include
    ${NCNN_ROOT}/${ANDROID_ABI}/include/ncnn
)

target_link_libraries(engine_jni
    ${NCNN_LIB}
    ${Vulkan_LIBRARIES}
    log
    android
    jnigraphics
)

# package libncnn.so into the APK: copy it next to our output so gradle picks it up
add_custom_command(TARGET engine_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${NCNN_LIB}
            $<TARGET_FILE_DIR:engine_jni>/libncnn.so
)

target_compile_options(engine_jni PRIVATE -O3 -fvisibility=hidden)
target_compile_definitions(engine_jni PRIVATE
    NCNN_VULKAN=1
    _GLIBCXX_USE_CXX11_ABI=1
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(engine_jni PRIVATE -march=armv8-a+simd)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(engine_jni PRIVATE -march=armv7-a -mfpu=neon -mfloat-abi=softfp)
endif()
