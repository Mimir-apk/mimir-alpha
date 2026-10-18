cmake_minimum_required(VERSION 3.22.1)

project(mimir_ra_hash LANGUAGES C)

# The product only reads CHDs. Keep the vendored dependency static and do not
# build its standalone tools/tests inside the Android module.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(INSTALL_STATIC_LIBS OFF CACHE BOOL "" FORCE)
set(CHDR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CHDR_VERIFY_BLOCK_CRC ON CACHE BOOL "" FORCE)
add_subdirectory(vendor/libchdr EXCLUDE_FROM_ALL)

set(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/rcheevos")

add_library(mimir_ra_hash SHARED
    mimir_chd_reader.c
    mimir_ra_hash_jni.c
    "${RCHEEVOS_DIR}/src/rc_compat.c"
    "${RCHEEVOS_DIR}/src/rhash/cdreader.c"
    "${RCHEEVOS_DIR}/src/rhash/hash.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_disc.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_rom.c"
    "${RCHEEVOS_DIR}/src/rhash/md5.c"
)

target_compile_features(mimir_ra_hash PRIVATE c_std_99)
target_compile_definitions(mimir_ra_hash PRIVATE
    RC_HASH_NO_ENCRYPTED
    RC_HASH_NO_ZIP
)
target_compile_options(mimir_ra_hash PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
)
target_include_directories(mimir_ra_hash PRIVATE
    "${RCHEEVOS_DIR}/include"
    "${RCHEEVOS_DIR}/src"
    "${RCHEEVOS_DIR}/src/rhash"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libchdr/include"
)
target_link_libraries(mimir_ra_hash PRIVATE chdr-static)
