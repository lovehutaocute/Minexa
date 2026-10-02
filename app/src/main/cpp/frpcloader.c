/*
 * libfrpcloader.so —— frpc 启动器（dlopen 版）
 *
 * 背景：Android targetSdk >= 29 起，SELinux 对 app 私有数据目录（app_data_file）
 * 拒绝 execute_no_trans，`execve()` 一个放在 filesDir 下的 ELF 会直接 EACCES。
 * 但 `dlopen()` 同一个 `.so` 是允许的，而 nativeLibraryDir（lib 目录）里的 ELF
 * 仍可执行。
 *
 * 于是把这个极小的 PIE 可执行文件编成「可执行 ELF，但命名为 libfrpcloader.so」——
 * Android Gradle 插件按 *.so 收集 native 产物并打进 lib/<abi>/，安装后落在
 * applicationInfo.nativeLibraryDir（允许执行）。由它去 dlopen 数据目录里的
 * libfrpc.so，引擎本体便可放在可写、可热更、可独立于 APK 升级的数据目录里。
 *
 * 文件后缀是 .so 不影响它是可执行 ELF：内核按 PT_INTERP 决定如何 exec，与扩展名无关。
 *
 * libfrpc.so 是 Go 以 c-shared 方式编译的自包含库（内置 cmd/frpclib 的 RunFrpc），
 * 静态链接了全部 Go 依赖，运行期不依赖额外 .so，因此无需 LD_PRELOAD 预加载。
 *
 * 停止：不在此装信号 handler。libfrpc.so 内部已用 signal.Notify 监听 SIGINT/SIGTERM
 * 并触发 GracefulClose；Go 运行时被 dlopen 后会接管信号，此处再装反而冲突。
 * Java 侧 `Process.destroy()` 发 SIGTERM 即可优雅停止。
 *
 * 与 Java 侧的约定：
 *   env MINESERVE_FRPC_LIB  必填。libfrpc.so 的绝对路径。
 *   argv[1]                 必填。frpc 配置文件的绝对路径（toml/yaml/json）。
 *   cwd                     工作目录，使配置里的相对路径有确定基准。
 */

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "MinexaFrpcLoader"

/* 日志同时进 logcat 与 stderr：stderr 会被 Java 侧合并进 stdout 统一成一条日志流，
 * 崩在 dlopen 之前也就能在应用内的隧道日志里看到原因，而不是只能在 logcat 里找。 */
#define LOGE(...)                                                    \
    do {                                                             \
        __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);    \
        fprintf(stderr, "[frpcloader] ");                            \
        fprintf(stderr, __VA_ARGS__);                                \
        fputc('\n', stderr);                                         \
        fflush(stderr);                                              \
    } while (0)

#define LOGI(...)                                                    \
    do {                                                             \
        __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__);     \
        fprintf(stdout, "[frpcloader] ");                            \
        fprintf(stdout, __VA_ARGS__);                                \
        fputc('\n', stdout);                                         \
        fflush(stdout);                                              \
    } while (0)

/* 对应 libfrpc.so 导出的 //export RunFrpc：int RunFrpc(char *configPath)。
 * 阻塞运行直到服务停止，返回退出码（0 正常，非 0 出错）。 */
typedef int (*run_frpc_t)(char *config_path);

int main(int argc, char **argv) {
    const char *frpc_lib = getenv("MINESERVE_FRPC_LIB");
    if (frpc_lib == NULL || frpc_lib[0] == '\0') {
        LOGE("未设置环境变量 MINESERVE_FRPC_LIB，无法定位 libfrpc.so");
        return 1;
    }
    if (argc < 2 || argv[1] == NULL || argv[1][0] == '\0') {
        LOGE("缺少配置文件路径参数 argv[1]");
        return 1;
    }

    LOGI("加载引擎 %s", frpc_lib);
    LOGI("配置文件 %s", argv[1]);

    void *handle = dlopen(frpc_lib, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        const char *dl_err = dlerror();
        LOGE("dlopen(%s) 失败: %s", frpc_lib, dl_err ? dl_err : "(无错误信息)");
        return 1;
    }

    /* 清空可能残留的错误状态，避免把上一个错误误当成本次 dlsym 的结果。 */
    dlerror();
    run_frpc_t run_frpc = (run_frpc_t) dlsym(handle, "RunFrpc");
    if (run_frpc == NULL) {
        const char *dl_err = dlerror();
        LOGE("dlsym(RunFrpc) 失败: %s —— 该 .so 可能不是 frp 的 c-shared 构建产物",
             dl_err ? dl_err : "(无错误信息)");
        dlclose(handle);
        return 1;
    }

    int ret = run_frpc(argv[1]);
    LOGI("RunFrpc 返回 %d", ret);

    /* 不 dlclose：Go 运行时不支持卸载，进程随 main 返回整体退出即可。 */
    return ret;
}
