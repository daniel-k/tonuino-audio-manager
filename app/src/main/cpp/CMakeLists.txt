cmake_minimum_required(VERSION 3.22.1)
project(lamejni C)

set(CMAKE_C_STANDARD 99)

set(LAME_SRC
    lame/libmp3lame/bitstream.c
    lame/libmp3lame/encoder.c
    lame/libmp3lame/fft.c
    lame/libmp3lame/gain_analysis.c
    lame/libmp3lame/id3tag.c
    lame/libmp3lame/lame.c
    lame/libmp3lame/newmdct.c
    lame/libmp3lame/presets.c
    lame/libmp3lame/psymodel.c
    lame/libmp3lame/quantize.c
    lame/libmp3lame/quantize_pvt.c
    lame/libmp3lame/reservoir.c
    lame/libmp3lame/set_get.c
    lame/libmp3lame/tables.c
    lame/libmp3lame/takehiro.c
    lame/libmp3lame/util.c
    lame/libmp3lame/vbrquantize.c
    lame/libmp3lame/VbrTag.c
    lame/libmp3lame/version.c
)

add_library(lamejni SHARED
    lamejni.c
    ${LAME_SRC}
)

target_include_directories(lamejni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lame
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/include
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame
)

target_compile_definitions(lamejni PRIVATE HAVE_CONFIG_H)

target_compile_options(lamejni PRIVATE
    -O3
    -ffast-math
    -fstrict-aliasing
)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(lamejni PRIVATE -mfpu=neon -mfloat-abi=softfp)
endif()
