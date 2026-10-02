package com.mineserve.mobile.server.tunnel.frpc

/**
 * frpc 引擎的下载源配置。
 *
 * ## 为什么引擎要联网下载，而不是随 APK 内置
 *
 * 引擎不是 frp 的官方产物。官方 Release 只发布 `linux_arm64.tar.gz`，里面是普通
 * ELF 可执行文件 —— 可以被 `execve`，但**没有 `RunFrpc` 导出**，`dlopen` 拿不到符号。
 * 要能被 `libfrpcloader.so` 加载，必须用 Go 对 frp 源码的 `cmd/frpclib` 执行
 * `-buildmode=c-shared` 单独构建，而官方不为 Android/arm64 预先构建这个产物。
 *
 * 把这样一份自建二进制打进 APK 也能用，但会有两个代价：APK 体积固定多出十几 MB，
 * 且每次换 frp 版本都得重新发版。走下载则两者都免 —— 代价是需要一个稳定的分发地址。
 *
 * ## 地址为空时的行为
 *
 * [METADATA_URL] 未填写（仍为 [PLACEHOLDER_URL]）时，在线下载入口整体隐藏，
 * 只保留「从本地文件导入」。这样配置没就位时不会给用户一个点了必然失败的按钮。
 */
object FrpcEngineConfig {

    /**
     * 引擎元数据 JSON 地址。
     *
     * 取自 EdgeCube 的 frpc 运行时分发站（`EdgeCubePackage-Frpc` 仓库的发行包，
     * 由 `edgecubemc.com` 提供元数据）。响应形状（无 `{code,data}` 外壳）：
     *
     * ```json
     * {
     *   "id": "frpc0.70.1",
     *   "version": 1,
     *   "versionName": "2026-08-07",
     *   "packages": {
     *     "aarch64": {
     *       "urls": [
     *         { "type": "direct", "url": "https://…/frpc0.70.1-aarch64.ecpkg" },
     *         { "type": "web",    "url": "https://…/网盘页" }
     *       ],
     *       "sha256": "…",
     *       "size": 5797865,
     *       "arch": ["aarch64"]
     *     }
     *   }
     * }
     * ```
     *
     * 注意下载物是 `.ecpkg`（ZIP 容器），不是裸 `.so` —— 元数据里没有裸库直链。
     * 解包逻辑见 [FrpcRuntimeDownloader]。
     *
     * 若要换成分发自己的引擎，替换本地址即可，不必重新发版。
     */
    const val METADATA_URL = "https://edgecubemc.com/app/ecpkg/metadata/frp/frpc0.70.1.json"

    /**
     * 元数据里本应用对应的 package key。
     *
     * **是 `aarch64`，不是 `arm64-v8a`**：前者是这份元数据（以及 EdgeCube 的
     * `.ecpkg` 清单）使用的架构名，后者是 Android ABI 名。两者指同一个架构，
     * 但在这里必须用元数据的叫法去查表。
     */
    const val PACKAGE_KEY = "aarch64"

    /**
     * `.ecpkg` 内库文件的路径，取自包内清单的 `launcher.lib`（相对于架构目录）。
     *
     * 实际布局：`<架构目录>/lib/libfrpc.so`，即 aarch64 包里的 `aarch64/lib/libfrpc.so`。
     */
    const val ECPKG_LIB_SUFFIX = "lib/libfrpc.so"

    /** `.ecpkg` 内清单文件名，用于读出架构目录名。 */
    const val ECPKG_MANIFEST = "edgecube-package.json"

    /** `.ecpkg` 内表示当前设备的架构目录名。 */
    const val ECPKG_ARCH_DIR = "aarch64"

    /**
     * 是否已配置在线下载源。
     *
     * 地址是编译期常量，正常情况下总为 true。保留这个开关是为了让"临时关掉
     * 下载、只留本地导入"成为改一个常量的事，而不必翻改动逻辑。
     */
    val isConfigured: Boolean get() = METADATA_URL.isNotBlank()

    /**
     * 元数据地址的镜像前缀，按顺序回退。
     *
     * 与 `AppUpdateService.downloadUrls` 同一个理由：分发地址极可能落在 GitHub，
     * 而 GitHub 直连在部分网络下不可达。逐个试比让用户自己想办法可靠。
     * 第一个是原始地址，保证自建服务器（不需要反代）也能正常工作。
     */
    fun metadataUrls(): List<String> {
        if (!isConfigured) return emptyList()
        return listOf(
            METADATA_URL,
            "https://ghfast.top/$METADATA_URL",
            "https://gh-proxy.com/$METADATA_URL",
            "https://ghproxy.net/$METADATA_URL"
        )
    }

    /**
     * 包直链的镜像前缀，按顺序回退。
     *
     * 只对走 GitHub 的地址套反代：自建域名套 `ghfast.top/` 这类前缀只会得到一个 404，
     * 让每次下载都白跑一轮回退。
     */
    fun downloadUrls(url: String): List<String> {
        if (!url.contains("github.com") && !url.contains("githubusercontent.com")) {
            return listOf(url)
        }
        return listOf(
            url,
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
            "https://ghproxy.net/$url"
        )
    }
}
