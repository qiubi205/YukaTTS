cmake_minimum_required(VERSION 3.22.1)
project("yuka_tts")

add_library(yuka_tts_bridge SHARED
    tts_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(yuka_tts_bridge
    ${log-lib}
    android
)
