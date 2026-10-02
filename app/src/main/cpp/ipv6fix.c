/*
 * libipv6fix.so —— 在 Android 上让 JVM 认为「本机有 IPv6」
 *
 * ## 为什么需要它
 *
 * OpenJDK 判定「本机是否支持 IPv6」的唯一依据是 `IPv6_supported()`
 * （src/java.base/unix/native/libnet/net_util_md.c，Linux 分支）：
 *
 *     fd = socket(AF_INET6, SOCK_STREAM, 0);      // Android 上这一步是成功的
 *     if (fd < 0) return JNI_FALSE;
 *     close(fd);
 * #ifdef __linux__
 *     FILE *fP = fopen("/proc/net/if_inet6", "r");
 *     if (fP == NULL) return JNI_FALSE;           // ← 卡在这里
 *     bufP = fgets(buf, sizeof(buf), fP);
 *     fclose(fP);
 *     if (bufP == NULL) return JNI_FALSE;         // ← 或这里（文件为空）
 * #endif
 *     ...
 *
 * 这个返回值最终变成 `sun.nio.ch.Net.IPV6_AVAILABLE`（Net.java 静态块，一次定生死），
 * 再决定 `ServerSocketChannel` 以 INET6 还是 INET 打开。**Android 上 App 读不到
 * /proc/net/if_inet6**（每个 App 有自己的 net namespace，该文件缺失或为空），
 * 于是 JVM 认定「无 IPv6」→ 通道只按 IPv4(INET) 打开 → `server-ip=::` 在
 * `Net.checkAddress` 抛 UnsupportedAddressTypeException，服务端一启动就崩。
 *
 * AOSP 的 libcore 与 Pojav/FCL 血统的 Android JDK 构建是**编译期**把整段检查
 * 跳过的（见 PojavLauncherTeam/android-openjdk-build-multiarch
 * `patches/jre_17/android/26_skip_proc_net6_check.diff`，注释 "skip some checks as
 * AOSP does"）。Termux 的 openjdk 补丁集没有这一条，所以在 Termux JDK 上
 * IPv6 监听等于没有。本库不改 JVM 二进制，而是在**运行时**把那一次 fopen 重定向到
 * 一个确定存在且非空的可读文件 —— 效果与编译期跳过等价（JVM 只判 fopen 与 fgets 非空）。
 *
 * ## 用法
 *
 * 只用于**原生 Termux JVM**：`LD_PRELOAD=<nativeLibraryDir>/libipv6fix.so`。
 * 不要在 proot/Ubuntu 场景挂它 —— guest 里的 loader 是 glibc 的 ld.so，
 * 加载不了这个 bionic 构建的库，linker 找不到文件会直接让 java 起不来
 * （与 libheaptagfix.so 同样的约束）。
 *
 * 编译（见 .github/workflows/build-ipv6fix.yml）：
 *
 *     aarch64-linux-android26-clang -shared -fPIC -Oz \
 *         -ffunction-sections -fdata-sections \
 *         -Wl,--gc-sections -Wl,-z,max-page-size=16384 \
 *         -o libipv6fix.so ipv6fix.c
 *
 * 注意这里**不能**加 `-fvisibility=hidden`：本库靠导出 `fopen`/`fopen64` 抢占符号，
 * 隐藏了就等于什么都没做。工作流里有 `readelf --dyn-syms` 自检把守这一点。
 *
 * 产物只依赖 libc.so / libdl.so（NDK 驱动默认记录），二者在进程启动时已加载，
 * 不给 java 进程引入任何新的加载依赖。
 */

/*
 * 与 heaptagfix.c 一致：刻意不包含任何 libc 头文件。交叉编译时 <stddef.h> 之类
 * 会拉入 glibc 的 bits/ 头，而目标平台是 bionic。本库只用到有限几个 libc 符号，
 * 全部显式声明。
 */
typedef __SIZE_TYPE__ size_t;

#ifndef NULL
#define NULL ((void *)0)
#endif

extern void *dlsym(void *handle, const char *symbol);
extern void *dlopen(const char *filename, int flags);

/* bionic 的 fopen 系列返回不透明 FILE*，这里只需要指针本身 */
typedef struct _IO_FILE FILE;
typedef FILE *(*fopen_fn)(const char *, const char *);

/* bionic/glibc 的 RTLD_NEXT = (void *) -1：按「当前库之后」的顺序查找同名符号 */
#define RTLD_NEXT_HANDLE ((void *)-1)
#define RTLD_NOW_LOCAL 2

/* 被接管的路径：OpenJDK 用来探测 IPv6 的那一个 */
static const char TARGET_PATH[] = "/proc/net/if_inet6";

/*
 * 替身文件候选：只要「存在 + 可读 + 非空（fgets 能拿到一行）」即可，
 * 因为 JVM 只判 fopen 与 fgets 是否非空，不解析内容。
 * 按「一定存在」的把握从高到低排。
 */
static const char *const SPOOF_SOURCES[] = {
    "/proc/self/mounts",
    "/proc/self/status",
    "/proc/version",
    NULL,
};

/* 只在首次真正接管时往 fd 2 打一行，方便在服务端日志里确认它到底有没有生效 */
static int announced = 0;

extern long write(int fd, const void *buf, unsigned long count);

static void announce_once(void)
{
    if (announced) {
        return;
    }
    announced = 1;
    static const char msg[] =
        "[ipv6fix] /proc/net/if_inet6 intercepted: JVM will report IPv6 available\n";
    (void)write(2, msg, sizeof(msg) - 1);
}

/* 逐字符比较，避免引入 strcmp（会拉入 libc 的额外符号） */
static int same_path(const char *a, const char *b)
{
    if (a == NULL || b == NULL) {
        return 0;
    }
    while (*a != '\0' && *b != '\0') {
        if (*a != *b) {
            return 0;
        }
        ++a;
        ++b;
    }
    return *a == *b;
}

static fopen_fn load_real(const char *symbol)
{
    fopen_fn fn = (fopen_fn)dlsym(RTLD_NEXT_HANDLE, symbol);
    if (fn != NULL) {
        return fn;
    }
    /* RTLD_NEXT 理论上不会失败；真失败了也不能返回我们自己的符号（会无限递归），
     * 所以显式打开 libc 再从它里面取。 */
    void *libc = dlopen("libc.so", RTLD_NOW_LOCAL);
    if (libc == NULL) {
        return NULL;
    }
    return (fopen_fn)dlsym(libc, symbol);
}

static FILE *open_spoof(const char *mode, fopen_fn real)
{
    for (size_t i = 0; SPOOF_SOURCES[i] != NULL; ++i) {
        FILE *fp = real(SPOOF_SOURCES[i], mode);
        if (fp != NULL) {
            announce_once();
            return fp;
        }
    }
    return NULL;
}

/*
 * 导出符号必须显式 default visibility（工作流里的编译命令带 -fvisibility=hidden
 * 会把它藏起来 —— 那样 LD_PRELOAD 就静默失效了）。
 */
#define EXPORTED __attribute__((visibility("default")))

EXPORTED FILE *fopen(const char *path, const char *mode)
{
    fopen_fn real = load_real("fopen");
    if (real == NULL) {
        return NULL;
    }
    if (same_path(path, TARGET_PATH)) {
        FILE *fp = open_spoof(mode, real);
        if (fp != NULL) {
            return fp;
        }
    }
    return real(path, mode);
}

EXPORTED FILE *fopen64(const char *path, const char *mode)
{
    fopen_fn real = load_real("fopen64");
    if (real == NULL) {
        /* 某些 bionic 版本把 fopen64 做成 fopen 的别名：退一步用它，
         * 但要走我们自己的 fopen 实现会递归，所以只在它是独立符号时可用。
         * 取不到就交回调用方，让原行为继续。 */
        fopen_fn fallback = load_real("fopen");
        if (fallback == NULL) {
            return NULL;
        }
        if (same_path(path, TARGET_PATH)) {
            FILE *fp = open_spoof(mode, fallback);
            if (fp != NULL) {
                return fp;
            }
        }
        return fallback(path, mode);
    }
    if (same_path(path, TARGET_PATH)) {
        FILE *fp = open_spoof(mode, real);
        if (fp != NULL) {
            return fp;
        }
    }
    return real(path, mode);
}
