package com.easytier.jni

/**
 * EasyTier 的原生 JNI 入口。
 *
 * ## 为什么包名和类名是 `com.easytier.jni.EasyTierJNI`
 *
 * JNI 的符号名由**类的全限定名**决定，`libeasytier_android_jni.so` 导出的是
 * `Java_com_easytier_jni_EasyTierJNI_runNetworkInstance` 这类名字。我们无法给
 * `external fun` 指定符号名（除非改用 `RegisterNatives` 手动注册，那要维护一张方法表，
 * 上游一加方法就失效）。所以这里刻意沿用上游的包名/类名，保证「声明即对得上」。
 *
 * 也正因为如此，方法上必须 `@JvmStatic`：object 的普通成员会编译成实例方法，
 * 虽然符号名相同，但第二个参数是 `jobject` 而不是 `jclass`，配 upstream 的 C 签名仍可用 ——
 * 为稳妥起见与上游一致用静态形式。
 *
 * ## 为什么用 JNI 而不是子进程
 *
 * 服务器侧必须让节点**真正持有虚拟 IP**（`port_forward` 要 bind 它），而虚拟 IP 只能来自
 * `VpnService` 建立的 TUN，TUN 的 fd 也只有在同一进程内才能交给 EasyTier。
 * 另外 2026-10-24 真机实测表明：`no_tun` 节点声明 `ipv4` 会直接 `EADDRNOTAVAIL`，
 * 所以「不建 TUN」这条路在服务器场景根本不成立。
 */
object EasyTierJNI {

    /**
     * 库是否已加载。
     *
     * 引擎改为按需下载后，加载路径有两条：用户数据目录里已安装的引擎文件（常规路径），
     * 以及 APK 内置（旧版本升级上来、还没重新下载时的兜底）。**只在真正要用时才加载**：
     * 静态初始化时文件还不存在，类加载就失败，后面连"提示去下载"的机会都没有。
     *
     * 下载完成后再调 [ensureLoaded] 即可重新尝试（System.load 对同一文件重复加载会抛
     * UnsatisfiedLinkError，已加载时直接短路）。
     */
    @Volatile
    var loaded: Boolean = false
        private set

    private val loadLock = Any()

    /** 尝试加载引擎；已加载直接返回 true。 */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(loadLock) {
            if (loaded) return true
            // 常规路径：EasyTierEngineStore 下载并校验过的引擎文件
            val installed = runCatching {
                com.mineserve.mobile.server.tunnel.easytier.EasyTierEngineStore
                    .engineFile()
                    .takeIf { it.isFile }
            }.getOrNull()
            if (installed != null) {
                runCatching {
                    System.load(installed.absolutePath)
                    loaded = true
                }
            }
            if (loaded) return true
            // 兜底：随包内置（老版本 APK 升级上来、引擎还没下载）
            runCatching { System.loadLibrary("easytier_android_jni") }
                .onSuccess { loaded = true }
            return loaded
        }
    }

    /**
     * 启动一个网络实例。
     *
     * 注意顺序：**先启动实例、再塞 TUN fd**（见 [setTunFd]）。反过来的话，实例启动时
     * 虚拟 IP 还没有网卡承载，效果等同于 P0 里踩到的 `EADDRNOTAVAIL`。
     *
     * @param config TOML 配置全文
     * @return 0 成功
     */
    @JvmStatic
    external fun runNetworkInstance(config: String): Int

    /**
     * 把 `VpnService` 建好的 TUN 文件描述符交给实例。
     *
     * @param instanceName 必须与 TOML 里的 `instance_name` 完全一致
     * @param fd `ParcelFileDescriptor.fd`（**不要**用 detachFd，这里不转移所有权）
     * @return 0 成功
     */
    @JvmStatic
    external fun setTunFd(instanceName: String, fd: Int): Int

    /** 收集运行中的网络信息（JSON），用于取实例真正拿到的虚拟 IP 与代理网段。 */
    @JvmStatic
    external fun collectNetworkInfos(maxLength: Int): String?

    /** 保留指定实例、停掉其余实例。 */
    @JvmStatic
    external fun retainNetworkInstance(instanceNames: Array<String>?): Int

    /** 最近一次错误信息，为空表示没有错误。 */
    @JvmStatic
    external fun getLastError(): String?

    /** 停止所有实例。 */
    @JvmStatic
    fun stopAllInstances(): Int = retainNetworkInstance(null)
}
