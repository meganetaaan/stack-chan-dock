cmake_minimum_required(VERSION 3.22)
project(stackchan_sano C)
set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(SANO "${CMAKE_CURRENT_SOURCE_DIR}/sano")
file(GLOB OJT_SOURCES "${SANO}/openjtalk/*.c")
add_library(stackchan_sano SHARED sano-jni.c ${SANO}/saanotts.c ${SANO}/saanotts_stream.c
    ${SANO}/saanotts_int8.c ${SANO}/fft.c ${SANO}/g2p.c ${SANO}/jdict.c ${SANO}/accent.c
    ${SANO}/njd_rules.c ${SANO}/label_ids.c ${SANO}/saan_kanji.c ${OJT_SOURCES})
target_include_directories(stackchan_sano PRIVATE ${SANO} ${SANO}/openjtalk)
target_compile_definitions(stackchan_sano PRIVATE SAAN_INT8_ACT=1 CHARSET_UTF_8 LABEL_IDS_EXTERNAL_SCRATCH=1)
target_compile_options(stackchan_sano PRIVATE -O3)
target_link_libraries(stackchan_sano m)
target_link_options(stackchan_sano PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
