cmake_minimum_required(VERSION 3.22.1)
project(nuked_sc55_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NUKED_BACKEND
    nuked/backend/config.cpp
    nuked/backend/diagnostics.cpp
    nuked/backend/emu.cpp
    nuked/backend/file_hashing.cpp
    nuked/backend/file_io.cpp
    nuked/backend/lcd.cpp
    nuked/backend/mcu_interrupt.cpp
    nuked/backend/mcu_opcodes.cpp
    nuked/backend/mcu_timer.cpp
    nuked/backend/mcu.cpp
    nuked/backend/pcm.cpp
    nuked/backend/rom_io.cpp
    nuked/backend/rom.cpp
    nuked/backend/standard_romsets.cpp
    nuked/backend/submcu.cpp
    nuked/backend/sha/sha224-256.c
    nuked/common/gain.cpp
    nuked/common/path_util.cpp
    nuked/common/rom_loader.cpp
)

add_library(nuked-sc55 SHARED native_bridge.cpp ${NUKED_BACKEND})
target_include_directories(nuked-sc55 PRIVATE nuked/backend nuked/common)
target_compile_options(nuked-sc55 PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(nuked-sc55 android log)
