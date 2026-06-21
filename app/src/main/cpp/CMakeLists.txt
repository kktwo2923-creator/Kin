# 仅在 gradle 属性 ctsNative=true 时才参与构建(见 app/build.gradle.kts)。
# 默认不启用,避免 CI 无 NDK 时构建失败。
cmake_minimum_required(VERSION 3.22.1)
project(ctsguard LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ctsguard SHARED ctsguard.cpp)

# 体积与符号:去符号表、隐藏可见性,抬高逆向成本
target_compile_options(ctsguard PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(ctsguard PRIVATE -Wl,--gc-sections -Wl,--strip-all)

find_library(log-lib log)
target_link_libraries(ctsguard ${log-lib})
