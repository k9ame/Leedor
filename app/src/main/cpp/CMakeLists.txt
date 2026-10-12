cmake_minimum_required(VERSION 3.22.1)

project("leedor")

# Log library
find_library(
        log-lib
        log
)

include_directories(include)

add_library(
        leedor
        SHARED
        espeak_wrapper.c
)

# ---------------------------------------------------------------------------
# 16 KB 页对齐（Android 15+ / Google Play 强制要求）
#
# 从 2025-11-01 起，targetSdk 35 的应用必须以 16 KB 页对齐交付，
# 否则在 16 KB 页设备上无法加载，Play 也会拒收。
#
# NDK r27+ 默认就是 16384，但本项目固定用 NDK 25（默认 4096），
# 所以这里显式指定，避免依赖 NDK 版本。
# ---------------------------------------------------------------------------
target_link_options(leedor PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(
        leedor
        ${log-lib}
)

# 预编译的 espeak-ng（GPL-3.0，见 THIRD_PARTY_NOTICES.md）。
#
# 这个 .so 由 espeak-ng 官方源码（v1.53.0）用 NDK 25 自行编译，
# 链接参数带 -Wl,-z,max-page-size=16384，因此是 16 KB 页对齐的。
# 编译步骤见 docs/16kb-alignment.md。
#
# 历史：这里原来链接的是第三方预编译的 libttsespeak.so（com.reecedunn.espeak），
# 它只有 4 KB 对齐，在 Android 15+ 的 16 KB 页设备上无法加载。
add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libespeak-ng.so)

target_link_libraries(
        leedor
        espeak-ng
        ${log-lib}
)
