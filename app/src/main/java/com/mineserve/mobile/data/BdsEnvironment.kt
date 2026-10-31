package com.mineserve.mobile.data

/**
 * BDS 运行环境的初始化阶段（顺序即执行顺序）。
 *
 * 界面按这个枚举显示「当前执行阶段」，因此枚举本身就是进度契约：
 * 新增步骤必须加在这里，不能只在日志里打一行了事。
 */
enum class BdsEnvStage {
    /** 准备 glibc 容器（PRoot Ubuntu rootfs） */
    Container,

    /** 下载并安装 box64 转译器 */
    Box64,

    /** 在容器里实测 box64 能否执行 */
    Verify;

    /** 该阶段的进度区间（占比），用于把整条进度条铺满 */
    val range: IntRange
        get() = when (this) {
            Container -> 0..60
            Box64 -> 60..92
            Verify -> 92..100
        }
}

/** 初始化失败的原因分类。界面按类型给文案，[rawDetail] 只作为补充说明。 */
enum class BdsEnvError {
    /** 应用私有分区空间不足 */
    Space,

    /** 容器 rootfs 无法准备（下载全失败 / 解压失败 / proot 不可用） */
    Container,

    /** 所有 box64 下载源都不可用 */
    Download,

    /** 下载到了文件但内容无效（哈希不符 / 不是 deb / 架构不对） */
    Corrupt,

    /** 包内没有预期的可执行文件 */
    Unpack,

    /** 装完了但容器里跑不起来 */
    Verify,

    Unknown
}

/**
 * BDS 运行环境的可见状态。
 *
 * 这是**运行环境**的状态，不是服务器状态：它只描述「这台设备能不能跑官方 BDS」，
 * 与某个具体服务端目录无关。已就绪时启动 BDS 不需要再做任何初始化。
 */
data class BdsEnvStatus(
    /** 环境完整可用（容器 + box64 都在位） */
    val installed: Boolean = false,
    val installing: Boolean = false,
    val stage: BdsEnvStage? = null,
    /** 整条进度 0..100 */
    val percent: Int = 0,
    /** 当前命中的下载源标签；换源时界面能看到变化 */
    val source: String? = null,
    /** 中性补充信息（版本号、文件名），不参与翻译 */
    val detail: String? = null,
    val box64Version: String? = null,
    val distroLabel: String? = null,
    val error: BdsEnvError? = null,
    /** 原始异常文本，只作次级说明（可能含英文/URL） */
    val rawDetail: String? = null,
    /** 逐个下载源的尝试结果，失败时展示 */
    val attempts: List<String> = emptyList()
) {
    companion object {
        val Idle = BdsEnvStatus()
    }
}

/**
 * box64 的下载源。
 *
 * box64 不在任何 Linux 发行版的官方源里，唯一公开分发的 apt 仓库就是这个
 * （原 `ryanfortner/box64-debs`，站点已迁到 `rfort.net`，旧地址仍会 301 过去）。
 * 三条入口分别覆盖三类故障：
 *  - `rfort.net` HTTPS：正常情况；
 *  - `rfort.net` HTTP：TLS 被中间盒打断/证书链异常的设备（明文通道走裸 socket，
 *    不受 Android 明文策略限制）；
 *  - `ryanfortner.github.io`：站点再次换域名时，旧入口还能顶一段时间。
 *
 * 这三条都是同一个仓库的不同入口，**不是**三个互不相干的软件源 ——
 * 因此下载后会按索引里的 SHA256 校验，内容不对照样换下一个。
 * 将来出现真正独立的镜像，只要往这个列表里加一条。
 */
object BdsSources {

    private const val DEB_PATH = "/box64-debs/debian/"

    /**
     * box64 的 `.deb` 来源。
     *
     * 前两条是 rfort.net 的两个协议入口（TLS 被中间盒打断时用 HTTP 明文），
     * 后三条走 jsDelivr —— 那个仓库本身托管在 GitHub 上，jsDelivr 是**另一个
     * 独立的 CDN**（有国内节点），rfort.net 在国内移动网络下经常连不上或极慢，
     * 此时换到 jsDelivr 通常几秒就下完。三组 jsDelivr 子域是各自独立的边缘，
     * 某一个被污染时换另一个仍然可用。
     *
     * 所有入口都是同一个仓库的镜像，因此下载后一律按索引里的 SHA-256 校验：
     * 哈希对上才说明内容确实一致。
     */
    val box64Deb: List<DownloadSource>
        get() = listOf(
            DownloadSource("rfort.net", "https://rfort.net$DEB_PATH"),
            DownloadSource("jsDelivr", "https://cdn.jsdelivr.net/gh/ryanfortner/box64-debs@master/debian/"),
            DownloadSource("jsDelivr (fastly)", "https://fastly.jsdelivr.net/gh/ryanfortner/box64-debs@master/debian/"),
            DownloadSource("jsDelivr (gcore)", "https://gcore.jsdelivr.net/gh/ryanfortner/box64-debs@master/debian/"),
            DownloadSource("rfort.net (HTTP)", "http://rfort.net$DEB_PATH"),
            DownloadSource("GitHub Pages", "https://ryanfortner.github.io$DEB_PATH")
        )

    /** Packages 索引地址 */
    fun indexUrl(source: DownloadSource): String = source.url + "Packages"

    /** 具体的 .deb 地址（索引里给的是相对路径） */
    fun debUrl(source: DownloadSource, fileName: String): String = source.url + fileName
}

/**
 * Ubuntu Base rootfs 的下载源（BDS 容器与「PRoot Java」共用同一批）。
 *
 * 全部是国内镜像：rootfs 有 30MB 上下，走海外源在移动网络下基本不可用。
 * 每个镜像都在同目录提供 `SHA256SUMS`，下载后据此校验 ——
 * 这能把「镜像返回了错误页」「下载被截断」这两种最常见的坏文件挡在解压之前。
 */
object UbuntuImageSources {

    private val mirrors = listOf(
        "清华镜像 (TUNA)" to "https://mirrors.tuna.tsinghua.edu.cn",
        "中科大镜像 (USTC)" to "https://mirrors.ustc.edu.cn",
        "北外镜像 (BFSU)" to "https://mirrors.bfsu.edu.cn",
        "南京大学镜像" to "https://mirror.nju.edu.cn",
        "上交镜像 (SJTU)" to "https://mirror.sjtu.edu.cn",
        "阿里云镜像" to "https://mirrors.aliyun.com"
    )

    /** 发行版对应的 Ubuntu 版本号（如 `22.04`） */
    fun release(distro: ProotDistro): String = when (distro) {
        ProotDistro.Focal -> "20.04"
        ProotDistro.Jammy -> "22.04"
    }

    /** rootfs 归档文件名 */
    fun archiveName(distro: ProotDistro): String = when (distro) {
        ProotDistro.Focal -> "ubuntu-base-20.04.5-base-arm64.tar.gz"
        ProotDistro.Jammy -> "ubuntu-base-22.04.5-base-arm64.tar.gz"
    }

    private fun releaseDir(distro: ProotDistro): String =
        "/ubuntu-cdimage/ubuntu-base/releases/${release(distro)}/release"

    /** 归档的全部来源，按优先级排列 */
    fun archiveSources(distro: ProotDistro): List<DownloadSource> {
        val archive = archiveName(distro)
        val dir = releaseDir(distro)
        return mirrors.map { (label, host) -> DownloadSource(label, "$host$dir/$archive") }
    }

    /** 与归档同源的 `SHA256SUMS` 地址（同一次询问里换了源，校验文件也得跟着换） */
    fun sha256SumsUrl(source: DownloadSource): String {
        val base = source.url.substringBeforeLast('/')
        return "$base/SHA256SUMS"
    }

    /**
     * 从 `SHA256SUMS` 文本里取出某个文件的哈希。
     *
     * 格式是 `哈希  文件名`（空格分隔，文件名不带路径），也有镜像写成
     * `哈希 *文件名`（二进制模式）。两种都要认，取不到就返回 null，
     * 调用方会退回「结构性校验」而不是直接失败。
     */
    fun expectedSha256(sumsText: String, fileName: String): String? =
        sumsText.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                val parts = line.split(Regex("\\s+"))
                parts.size >= 2 && (
                    parts.last() == fileName ||
                        parts.last() == "*$fileName" ||
                        parts.last().endsWith("/$fileName")
                    )
            }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.takeIf { it.length == 64 && it.all { ch -> ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F' } }
            ?.lowercase()
}
