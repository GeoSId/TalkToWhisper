cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# ── Resolve the whisper.cpp source directory ──────────────────────────────────
# The source lives at <project_root>/whisper-cpp-src/ (set up by setup_whisper_cpp.sh).
# From this CMakeLists.txt (app/src/main/jni/whisper/) the project root is 5 levels up.
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../..)

# Verify it looks right
if(NOT EXISTS "${WHISPER_LIB_DIR}/whisper-cpp-src/src/whisper.cpp")
    message(FATAL_ERROR
        "whisper.cpp source not found at ${WHISPER_LIB_DIR}/whisper-cpp-src/.\n"
        "Run  ./setup_whisper_cpp.sh  from the project root first."
    )
endif()

set(WHISPER_SRC_DIR ${WHISPER_LIB_DIR}/whisper-cpp-src)

# ── Source files ──────────────────────────────────────────────────────────────
set(
    SOURCE_FILES
    ${WHISPER_SRC_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

# ── Build function (creates one shared library target) ────────────────────────
function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif ()

    # ── ALWAYS optimise the native code (-O3) even in Debug builds ─────
    # whisper.cpp + ggml are heavy numerical code — running at -O0 makes
    # inference 10-30× slower (30 s for a word instead of ~1 s).
    # The Java/Kotlin debug build type should NOT affect native perf.
    target_compile_options(${target_name} PRIVATE -O3)
    target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

    target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
    target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
    target_link_options(${target_name} PRIVATE -flto)

    # ── ggml (the tensor library whisper.cpp depends on) ──────────────────
    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_SRC_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    # Also optimise ggml — it does the actual matrix multiplications.
    target_compile_options(ggml PRIVATE -O3 ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)

endfunction()

# ── Build optimised variants for ARM architectures ────────────────────────────
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

# Default target (always built)
build_library("whisper")

# ── Include directories ───────────────────────────────────────────────────────
include_directories(${WHISPER_SRC_DIR})
include_directories(${WHISPER_SRC_DIR}/src)
include_directories(${WHISPER_SRC_DIR}/include)
include_directories(${WHISPER_SRC_DIR}/ggml/include)
include_directories(${WHISPER_SRC_DIR}/ggml/src)
include_directories(${WHISPER_SRC_DIR}/ggml/src/ggml-cpu)
