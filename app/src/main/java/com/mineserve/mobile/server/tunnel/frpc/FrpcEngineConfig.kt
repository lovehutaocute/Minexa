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
     * 引擎元数据 JSON 地址（**首选猜测**，真实地址见 [CATALOG_URL] 的说明）。
     *
     * 取自 EdgeCube 的 frpc 运行时分发站（`EdgeCubePackage-Frpc` 仓库的发行包，
     * 由 `edgecubemc.com` 提供元数据）。响应形状（无 `{code,data}` 外壳）：
     *
     * ```json
     * {
     *   "id": "frpc0.69.1",
     *   "version": 1,
     *   "versionName": "2026-07-05",
     *   "packages": {
     *     "aarch64": {
     *       "urls": [
     *         { "name": "GitHub", "type": "direct", "url": "https://github.com/…/frpc0.69.1-aarch64.ecpkg" },
     *         { "name": "Gitee",  "type": "direct", "url": "https://gitee.com/…/frpc0.69.1-aarch64.ecpkg" },
     *         { "name": "木韩网盘", "type": "web",   "url": "https://pan.mcbebbs.cn/s/…" }
     *       ],
     *       "sha256": "…",
     *       "size": 5725468,
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
    const val METADATA_URL = "https://edgecubemc.com/app/ecpkg/metadata/frp/frpc0.69.1.json"

    /**
     * ecpkg 目录清单，用于**自动发现** frpc 元数据地址。
     *
     * 为什么需要它：`edgecubemc.com` 是 SPA，未知路径不会 404，而是回落成
     * `index.html`（HTTP 200 + `text/html`）。所以把版本号写死在 [METADATA_URL] 里，
     * 上游一升版本（0.69.1 → 0.70.1…）本应用就会拿到一份网页，报出
     * 「元数据格式无法识别」—— HTTP 层一切正常，只有内容不对，最难查的一类失败。
     *
     * 有了目录清单，写死的常量化作「首选猜测」，真正的地址每次从这份
     * `list.json` 里现取（`type == "frpc"` 那一组的 `packages`）。
     */
    const val CATALOG_URL = "https://edgecubemc.com/app/ecpkg/list.json"

    /**
     * 引擎发行页（给人点开手动下载用）。
     *
     * 用 Gitee 而不是 GitHub：这条路径存在的意义就是「在线下载失败时的兜底」，
     * 而失败最常见的原因正是 GitHub 不可达。页面上 `frpc*-aarch64.ecpkg`
     * 可以直接下载导入 —— [FrpcRuntime.install] 认这个容器格式。
     */
    const val RELEASE_PAGE = "https://gitee.com/venti1112/EdgeCubePackage-Frpc/releases"

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
     * 元数据地址的候选列表，按顺序回退。
     *
     * 与 `AppUpdateService.downloadUrls` 同一个理由：分发地址可能落在 GitHub，
     * 而 GitHub 直连在部分网络下不可达。直接复用 [downloadUrls]：非 GitHub 的自建地址
     * 只会原样返回，避免套上 `ghfast.top/` 得到一个必然 404 的额外重试。
     */
    fun metadataUrls(): List<String> = downloadUrls(METADATA_URL)

    /** 目录清单的候选地址（同样只对 GitHub 地址套反代）。 */
    fun catalogUrls(): List<String> = downloadUrls(CATALOG_URL)

    /**
     * 包直链的镜像前缀，按顺序回退。
     *
     * 只对走 GitHub 的地址套反代：自建域名套 `ghfast.top/` 这类前缀只会得到一个 404，
     * 让每次下载都白跑一轮回退。原始地址排第一，保证自建 / 直连也能正常工作。
     */
    fun downloadUrls(url: String): List<String> {
        if (!url.contains("github.com") && !url.contains("githubusercontent.com")) {
            return listOf(url)
        }
        return listOf(
            url,
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
            "https://cdn.gh-proxy.com/$url",
            "https://ghproxy.net/$url"
        )
    }

    /** 国内直连通常比「GitHub 原始地址 + 反代」更快的域名。 */
    private val DOMESTIC_HOSTS = listOf("gitee.com", "gitee.io")

    /**
     * 把国内可达的源提到前面（Gitee）。
     *
     * 元数据里 aarch64 包的直链顺序是 GitHub → Gitee。GitHub 直连在部分网络下
     * 不是「拒绝」而是**静默超时**，要等满 [FrpcRuntimeDownloader] 的 15 秒连接超时
     * 才轮到 Gitee —— 5.7MB 的包本来十几秒就能下完。
     *
     * 用稳定排序（`sortedBy` 保序），同档内维持元数据给的优先级；
     * Gitee 万一也挂了，后面的 GitHub + 反代仍会照常回退。
     */
    fun preferDomesticFirst(urls: List<String>): List<String> =
        urls.sortedBy { url ->
            when {
                DOMESTIC_HOSTS.any { url.contains(it) } -> 0
                else -> 1
            }
        }
}
