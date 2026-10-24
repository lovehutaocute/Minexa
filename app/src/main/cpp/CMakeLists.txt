cmake_minimum_required(VERSION 3.18.1)

project("mineserve_frp" C)

set(CMAKE_C_STANDARD 11)

# 16KB 页设备的 ELF 对齐要求（Android 15+）。加在全局：所有产物统一吃这条。
add_link_options(-Wl,-z,max-page-size=16384)

# ─── libfrpcloader.so ─── frpc 启动器（dlopen 数据目录里的 libfrpc.so）
#
# 关键技巧：产物是**可执行文件**，但命名为 `libfrpcloader.so`。
# Android Gradle 插件按 *.so 收集 native 产物并打进 lib/<abi>/，安装后位于
# applicationInfo.nativeLibraryDir —— Android 允许从该目录 execve ELF，
# 而 app 数据目录（filesDir）自 API 29 起禁止执行。
# 文件后缀是 .so 不影响它是可执行 ELF：内核按 PT_INTERP 决定如何 exec。
#
# 为什么需要它：frpc 引擎（libfrpc.so，Go c-shared，约 15MB）要放在可写、
# 可独立于 APK 热更的数据目录里，但数据目录里的 ELF 不能直接执行。靠这个
# 常驻 lib/ 目录的小启动器去 dlopen 它，两个限制同时满足。
add_executable(frpcloader frpcloader.c)

set_target_properties(frpcloader PROPERTIES
        PREFIX "lib"
        SUFFIX ".so"
        OUTPUT_NAME "frpcloader")

# 显式声明 PIE：需要生成可被内核 exec 的 PT_INTERP 入口。
# max-page-size=16384：16KB 页设备的 ELF 对齐要求（Android 15+），不加会在
# 系统的「应用兼容性」清单里挂上"LOAD 区段未对齐"。
target_compile_options(frpcloader PRIVATE -fPIE)
target_link_options(frpcloader PRIVATE -fPIE -pie -Wl,-z,max-page-size=16384)

# dl：dlopen/dlsym/dlerror；log：__android_log_print（便于用 logcat 排查启动失败）。
target_link_libraries(frpcloader dl log)
