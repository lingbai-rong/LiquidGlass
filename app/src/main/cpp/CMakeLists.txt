cmake_minimum_required(VERSION 3.22.1)
project(liquidglass_core C)

# Keep the native target explicit so Gradle's externalNativeBuild remains reproducible.
add_library(liquidglass_core SHARED native_core.c)

find_library(jnigraphics-lib jnigraphics)

target_compile_options(liquidglass_core PRIVATE
        -O3
        -flto
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -fno-ident)

target_link_options(liquidglass_core PRIVATE
        -flto
        -Wl,--gc-sections
        -Wl,--strip-all)

target_link_libraries(liquidglass_core PRIVATE ${jnigraphics-lib} dl)
