package com.mineserve.mobile.data

import kotlinx.serialization.Serializable

/**
 * 服务端核心类型：Paper / Purpur / Leaves / Leaf / Spigot / CraftBukkit / Fabric / Forge / NeoForge / Quilt / Vanilla / Velocity / BungeeCord
 */
enum class PropertiesMode { JavaProperties, PowerNukkitXYaml, Unsupported }

@Serializable
enum class ServerCore(val displayName: String) {
    Paper("Paper"),
    Purpur("Purpur"),
    Leaves("Leaves"),
    Leaf("Leaf"),
    Spigot("Spigot"),
    CraftBukkit("CraftBukkit"),
    Fabric("Fabric"),
    Forge("Forge"),
    NeoForge("NeoForge"),
    Quilt("Quilt"),
    Vanilla("Vanilla"),
    Velocity("Velocity"),
    BungeeCord("BungeeCord"),
    PowerNukkitX("PowerNukkitX"),
    PocketMine("PocketMine-MP"),
    Allay("Allay"),
    Unknown("未知");

    /** 是否为基岩版（UDP 网络、Bedrock 客户端连接） */
    val isBedrock: Boolean get() = this == PowerNukkitX || this == PocketMine || this == Allay

    /** 控制台保存世界的命令：基岩版（PowerNukkitX）不支持 save-all，用 save hold */
    val consoleSaveCommand: String get() = if (isBedrock) "save hold" else "save-all"

    val supportsPlugins: Boolean get() = this == Paper || this == Purpur ||
        this == Leaves || this == Leaf || this == Spigot || this == CraftBukkit || this == PowerNukkitX

    /** 是否支持 Fabric/Forge 模组体系 */
    val supportsMods: Boolean get() = this == Fabric || this == Forge || this == NeoForge || this == Quilt

    val propertiesMode: PropertiesMode
        get() = when (this) {
            PowerNukkitX -> PropertiesMode.PowerNukkitXYaml
            Allay -> PropertiesMode.Unsupported
            Unknown -> PropertiesMode.Unsupported
            else -> PropertiesMode.JavaProperties
        }

    /** 是否需 installer 流程（下载 installer.jar 后需执行安装命令生成启动环境） */
    val needsInstaller: Boolean get() = this == Forge || this == NeoForge || this == Quilt
}

/**
 * 内网穿透方式
 * - Frp: 自建 frp 服务器，最灵活，功能最全
 * - Bore: 自建 bore 服务器，协议极简，纯 Kotlin 实现无需 Termux
 * - SakuraFrp: SakuraFrp 平台，填 Token 自动拉取隧道配置，无需自建服务器
 * - MeFrp: ME Frp 平台，填 Token 自动拉取隧道配置，免费节点，无需自建服务器
 */
@Serializable
enum class TunnelType(val displayName: String, val description: String) {
    Frp("frp", "自建服务器，功能最全，支持自定义端口"),
    Bore("bore", "自建服务器，协议最简，纯手机端运行无需下载二进制"),
    Stun("STUN 直连", "无需服务器，全锥形 NAT 下直连，带宽即家宽上行"),
    SakuraFrp("SakuraFrp", "填入 Token 管理隧道，官方节点，无需自建服务器"),
    MeFrp("ME Frp", "填入 Token 管理隧道，免费节点，无需自建服务器")
}

/**
 * 隧道运行状态枚举（与 TunnelManager 解耦，可直接用于 UI）
 */
enum class TunnelStatus {
    Idle,       // 未启动
    Starting,   // 正在启动
    Running,    // 运行中
    Failed,     // 失败
    Stopped     // 已手动停止
}

/**
 * 隧道运行时状态快照（由 TunnelManager 推送至 StateFlow）
 */
data class TunnelState(
    val isRunning: Boolean = false,
    val publicUrl: String = "",
    val status: TunnelStatus = TunnelStatus.Idle,
    val errorMessage: String = "",
    val activeType: TunnelType? = null,
    val activeConnections: Int = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0
)

/**
 * Termux bootstrap rootfs 下载源
 * - Auto: 按顺序尝试所有镜像（镜像优先）
 * - 其余: 指定镜像优先，其余回退
 */
@Serializable
enum class DownloadMirror(val displayName: String, val baseUrl: String) {
    Auto("自动（镜像优先）", ""),
    GitHub("GitHub 直连", "https://github.com/termux/termux-packages/releases/download"),
    GhProxy("gh-proxy.com", "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download"),
    MirrorGhproxy("mirror.ghproxy.com", "https://mirror.ghproxy.com/https://github.com/termux/termux-packages/releases/download"),
    GhproxyNet("ghproxy.net", "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download"),
    Moeyy("github.moeyy.xyz", "https://github.moeyy.xyz/https://github.com/termux/termux-packages/releases/download"),
    Api99988866("gh.api.99988866.xyz", "https://gh.api.99988866.xyz/https://github.com/termux/termux-packages/releases/download"),
    Ghfast("ghfast.top", "https://ghfast.top/https://github.com/termux/termux-packages/releases/download")
}

/**
 * Termux apt 软件源（JDK/wget/frp 等依赖包下载）
 * 默认使用清华镜像，切换后下次初始化时生效。
 */
@Serializable
enum class AptMirror(val displayName: String, val url: String) {
    Tuna("清华镜像 (TUNA)", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
    Aliyun("阿里云镜像", "https://mirrors.aliyun.com/termux/apt/termux-main"),
    Ustc("中科大镜像 (USTC)", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
    Nju("南京大学镜像", "https://mirror.nju.edu.cn/termux/apt/termux-main"),
    // Kept only for old DataStore values; new installs never select an overseas APT source.
    Official("清华镜像 (旧配置)", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main")
}

/**
 * 依赖安装步骤
 * 注：已去掉 Tmux 步骤（改用 Android 原生 ProcessBuilder 管理进程）
 */
@Serializable
enum class InstallStep(val label: String) {
    Jdk("JDK 17 运行环境"),
    Wget("Wget 下载工具"),
    Rclone("Rclone 远程挂载"),
    Proot("PRoot 兼容层")
}

@Serializable
enum class JavaVersion(val displayName: String, val packageName: String, val directoryName: String) {
    Java8("Java 8", "ubuntu-java8", "java-8-ubuntu"),
    /** TUR 社区仓库提供的 Termux 原生 openjdk-11（Forge 1.13–1.16 的推荐运行时）。 */
    Java11("Java 11", "openjdk-11", "java-11-openjdk"),
    Java17("Java 17", "openjdk-17", "java-17-openjdk"),
    Java21("Java 21", "openjdk-21", "java-21-openjdk"),
    Java25("Java 25", "openjdk-25", "java-25-openjdk")
}

/**
 * PRoot 来宾发行版（rootfs 画像）。
 *
 * ## 为什么要有这个枚举
 *
 * 在这之前，PRoot 只服务于**一个**用途：在 Ubuntu 20.04 里跑 Java 8。
 * 于是 rootfs 路径（`java8-ubuntu-rootfs`）、就绪标记（`java-8-ubuntu-ready`）、
 * 来宾 JAVA_HOME（`/usr/lib/jvm/java-8-openjdk-arm64`）、apt 源代号（`focal`）
 * 全部硬编码在 `TermuxRuntime` 里。 *
 * 现在 PRoot 要作为**通用 Linux 容器**暴露给用户（第三种 [JavaVendor]），
 * 就得支持多个发行版，并把上面那批常量参数化 —— 这个枚举就是那组参数的唯一来源。
 *
 * ## 首期范围
 *
 * 只实现 [Focal]（Ubuntu 20.04）与 [Jammy]（Ubuntu 22.04）。
 * **刻意不预留 Debian/Bookworm 空壳条目**：列表里出现的每一项都必须是真能装上的，
 * 否则就成了「点了装不上」的死路（同 [JavaVendor.versions] 的原则）。
 *
 * ## 路径命名：每个发行版一个独立子目录
 *
 * 每个发行版在 `$PREFIX/var/lib/mineserve/` 下有**自己独立的**目录名
 * （`focal-rootfs` / `jammy-rootfs`），互不共享。
 *
 * 这是刻意的：早期 PRoot 只用于「Ubuntu 20.04 + Java 8」一种用途，
 * 路径直接叫 `java8-ubuntu-rootfs`，既编码了发行版又编码了 Java 版本 ——
 * 而同一个 rootfs 本就可以装任意受支持的 JDK。现在改成按发行版命名，
 * 「哪个 rootfs」与「里面装哪个 Java」彻底解耦。
 *
 * **代价（已知并接受）**：升级前已装好 Java 8 的用户，其 rootfs 仍在旧路径下，
 * 应用会显示「未安装」，需要重新下载安装一次。旧目录不会被自动删除 ——
 * 应用不再引用它，也不做自动清理（避免误删用户数据）；
 * 如需回收空间，可由用户手动删除 `var/lib/mineserve/java8-ubuntu-rootfs`。
 *
 * ## rootfs 来源
 *
 * 沿用既有的 Ubuntu Base 直下方案（清华 / 中科大镜像的 `ubuntu-cdimage`），
 * **不走 Docker Hub** —— 移动网络下 Docker Hub 经常返回 HTTP 429，
 * 而 Ubuntu 归档站是普通 HTTP 端点，不需要账号、不限速。
 */
@Serializable
enum class ProotDistro(
    /** 界面与日志里的显示名。刻意带上版本号，用户选的就是「哪个发行版」。 */
    val displayName: String,
    /** 来宾代号，用于拼 apt 源（`deb <mirror> <codename> main ...`）。 */
    val codename: String,
    /** rootfs 落点目录名（相对 `$PREFIX/var/lib/mineserve/`）。 */
    val dirName: String
) {
    Focal(
        displayName = "Ubuntu 20.04 (focal)",
        codename = "focal",
        dirName = "focal-rootfs"
    ),

    Jammy(
        displayName = "Ubuntu 22.04 (jammy)",
        codename = "jammy",
        dirName = "jammy-rootfs"
    );

    /**
     * 本发行版的 apt 源镜像。
     *
     * 只用国内镜像：PRoot 容器首次安装 JDK 要拉 100–200MB 的 deb，
     * 走海外源在移动网络下基本不可用。安装脚本会逐个尝试。
     */
    val aptMirrors: List<String> get() = listOf(
        "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
        "http://mirrors.ustc.edu.cn/ubuntu-ports"
    );

    /**
     * 本发行版**确实能通过 apt 装到**的 Java 版本。
     *
     * ## 为什么不能直接用 `JavaVersion.entries`
     *
     * 这张表是「上游包现实」的忠实记录，而不是我们的愿望清单：
     *
     * - `openjdk-8-jdk` 只存在于 focal；Jammy 的源里没有 Java 8。
     * - `openjdk-21-jdk` 只存在于 jammy；Focal 的源里没有 Java 21。
     * - **Java 25 在两者中都不存在** —— Ubuntu 官方 arm64 源根本没有 openjdk-25。
     *
     * 若照抄全版本，界面会列出装了必然失败的选项（用户点了只看到 apt 报
     * `Unable to locate package`，藏在日志深处，很容易被当成应用 bug）。
     * 因此 UI、安装、启动校验、诊断四处**都必须**从这张表派生，不得各自硬编码。
     *
     * 另注：Java 25 的缺失是**首期已确认的范围决策**，不是待办事项 ——
     * 文案口径应为「该容器不提供」，而不是「暂未支持」（后者暗示等更新就有）。
     */
    val availableJavaVersions: List<JavaVersion>
        get() = when (this) {
            Focal -> listOf(JavaVersion.Java8, JavaVersion.Java11, JavaVersion.Java17)
            Jammy -> listOf(JavaVersion.Java11, JavaVersion.Java17, JavaVersion.Java21)
        };

    /** 本发行版下「容器内 Java 版本」的完整坐标；版本不可装时返回 null。 */
    fun profileFor(version: JavaVersion): ProotContainerProfile? =
        availableJavaVersions.firstOrNull { it == version }
            ?.let { ProotContainerProfile(this, it) }

    /**
     * 胶囊切换控件里用的短名（「Ubuntu 20.04」）。
     *
     * [displayName] 带代号（「Ubuntu 20.04 (focal)」），在只有两个选项、每条
     * 只有一半宽度的胶囊里会挤成两行。代号本身在分组说明里已经出现过，
     * 这里省掉不丢信息。
     */
    val shortName: String
        get() = displayName.substringBefore(" (")

    companion object {
        /** 新装容器的默认发行版：Java 版本覆盖面更适合新核心（11/17/21）。 */
        val Default: ProotDistro = Jammy

        fun fromName(name: String?): ProotDistro? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Java 运行时的**来源分类**。
 *
 * 两套 JDK 是同一个东西的两个来源，都跑在同一个 Termux 环境里、共用同一套启动链路，
 * 差别只在「谁是 JDK 本体」：
 *
 * - [Termux]：Termux 生态的 openjdk（apt 装，Java 8 额外走 proot/Ubuntu 容器）。
 *   它的 `libnet.so` 在 Android 上判定「本机没有 IPv6」，因此需要 [JvmFlavor.PatchedJdk]
 *   那层兼容库才能监听 `::`。
 * - [Fcl]：FoldCraftLauncher 血统的 Android 原生 JRE（PojavLauncherTeam 构建，
 *   从 `.ecpkg` 包安装）。它的 `libnet.so` 编译期就跳过了 `/proc/net/if_inet6` 检查，
 *   `::` 直接可用，不需要兼容层；只依赖系统库（`libc/libz/libdl/libm`），不依赖 Termux 前缀。
 *
 * 分类是给用户看的「来源」，版本号才是能力差异 —— 因此 UI 上按来源分组、
 * 组内只显示纯版本号。
 */
@Serializable
enum class JavaVendor {
    /** Termux 官方/社区 openjdk（apt 安装，Java 8 走 proot 容器）。 */
    Termux,

    /** FoldCraftLauncher 血统的原生 JRE（.ecpkg 包，自带 IPv6 支持）。 */
    Fcl,

    /**
     * PRoot Linux 容器里的 glibc JDK（Ubuntu 20.04 / 22.04，apt 安装）。
     *
     * ## 与 [Termux] 的区别
     *
     * 两者都是 apt 装的 openjdk，但**运行在不同的 libc 世界里**：
     * [Termux] 用 bionic，跑在 Android 内核上直连；[Proot] 用 glibc，
     * 跑在 PRoot 用户态兼容层重写的 Ubuntu rootfs 里。这带来三个实际差异：
     *
     * 1. **IPv6 兼容层（[JvmFlavor.PatchedJdk]）对它无效** —— 那层是按 bionic
     *    构建的 `LD_PRELOAD` 库，guest 内是 glibc loader，加载不了。
     * 2. **来宾版本受发行版 apt 源限制**：见 [ProotDistro.availableJavaVersions]，
     *    首期 focal 提供 8/11/17、jammy 提供 11/17/21，**都不提供 Java 25**。
     * 3. 多一层 `proot` + `ptrace`，启动更慢、IO 有开销。
     *
     * ## 为什么它值得存在
     *
     * 服务端目录以 bind 方式进入容器（`/srv/mineserve`），所以世界、插件、备份、
     * 文件管理全部仍在原来的宿主路径上，应用其余部分一行都不用改；
     * 而用户拿到的是一个**标准 glibc Linux 环境**，能跑那些在 bionic 上
     * 水土不服的服务端与原生依赖。
     */
    Proot;

    /**
     * 该来源提供的版本列表。
     *
     * 三个来源各自如实列出**确实能装上**的版本，不虚构选项 ——
     * UI 分组时直接用它，不要再各处硬编码一份。
     *
     * - Termux：11/17/21/25。**刻意不含 Java 8** —— Java 8 在 Termux 下同样
     *   跑在 proot 容器里，把它列在这里会和「PRoot 容器」分组重复列同一个容器，
     *   用户会以为有两份而重复安装、或看不懂磁盘占用为何翻倍。
     *   它只出现在它真正所属的分组里（[Proot]）。
     * - FCL：上游只发布 17/21/25（没有 8 与 11）。
     * - Proot：取两个发行版可用版本的**并集**（focal 8/11/17 ∪ jammy 11/17/21），
     *   即 8/11/17/21。**不含 Java 25** —— 这不是遗漏，是 Ubuntu arm64 源
     *   确实没有该包。用户选到 Java 21 时，具体用哪个发行版由
     *   [McConfig.prootDistro] 决定，启动前会校验该发行版是否真的提供这个版本。
     *
     * ⚠️ 历史配置里仍可能有 `selectedJavaVendor = Termux` + `JavaVersion.Java8`
     * （该组合曾是这个分组下的唯一选项）。它**仍然可用**：见 [isProotGuestRuntime]，
     * 该组合会被正确路由到 focal 容器。列表里不再展示，是为了不再重复；
     * 已有配置不会因此失效。
     */
    val versions: List<JavaVersion>
        get() = when (this) {
            Termux -> listOf(
                JavaVersion.Java11,
                JavaVersion.Java17,
                JavaVersion.Java21,
                JavaVersion.Java25
            )
            Fcl -> listOf(JavaVersion.Java17, JavaVersion.Java21, JavaVersion.Java25)
            Proot -> listOf(
                JavaVersion.Java8,
                JavaVersion.Java11,
                JavaVersion.Java17,
                JavaVersion.Java21
            )
        }

    /**
     * 日志与诊断里用的短名。
     *
     * 刻意不本地化：这些字样会出现在控制台日志与崩溃报告里，用户拿去搜索/对比时
     * 必须与代码、文档里的叫法一致。界面上的分组标题另走 strings 资源。
     */
    val logName: String
        get() = when (this) {
            Termux -> "Termux"
            Fcl -> "FCL"
            Proot -> "PRoot"
        }

    /**
     * 该来源在**指定发行版**下真正可安装的版本。
     *
     * 与 [versions] 的区别只在 [Proot]：[versions] 是两个发行版的并集，
     * 而单个发行版只提供其中一部分（focal 无 21、jammy 无 8）。
     * 其余来源与发行版无关，直接返回 [versions]。
     *
     * 需要判断「这个来源能不能装这个版本」时必须用本方法 ——
     * 用 [versions] 会把 focal 装不上的 Java 21 判成可用，
     * 直到进容器跑 apt 才报 `Unable to locate package`。
     */
    fun availableVersions(distro: ProotDistro): List<JavaVersion> =
        if (this == Proot) distro.availableJavaVersions else versions
}

/**
 * 判断某个 (来源, 版本) 在指定发行版下是否真的能装上。
 *
 * 这是判断可安装性的**唯一入口**：`Proot` 受发行版限制，其余来源不受。
 */
fun JavaVendor.provides(version: JavaVersion, distro: ProotDistro): Boolean =
    version in availableVersions(distro)

/**
 * 一个具体的运行环境：**来源 + 版本**。
 *
 * 项目里凡是「当前用哪个 Java」的状态都应该是这个类型，而不是裸的 [JavaVersion] ——
 * 否则「Java 17」在两个来源下会指向同一个值，选中的到底是哪一套就分不清了。
 */
data class JavaRuntime(val vendor: JavaVendor, val version: JavaVersion) {

    /** 行内显示名。来源由分组标题表达，这里只给版本号。 */
    val displayName: String get() = version.displayName

    companion object {
        val Default = JavaRuntime(JavaVendor.Termux, JavaVersion.Java17)

        /** 全部可选运行环境，按来源分组顺序展开（Termux 在前）。 */
        val all: List<JavaRuntime>
            get() = JavaVendor.entries.flatMap { v -> v.versions.map { JavaRuntime(v, it) } }
    }
}

/**
 * 运行环境大类：Java 系核心跑 JVM，PocketMine-MP 跑 PHP。
 *
 * 启动设置抽屉的运行环境下拉、依赖管理页的 PHP 版本选择共用这一个枚举，
 * 避免各处各写一套判断。
 */
enum class RuntimeKind { Java, Php }

/**
 * PocketMine-MP 使用的 PHP 运行时。
 *
 * 说明：目前上游（ItzxDwi/AndroidPHP）只发布了 **一个** aarch64 预编译包，
 * PHP 版本固定为 8.2 —— 这同时是 PocketMine-MP 5 的硬性要求
 * （需要 chunkutils2 / encoding / leveldb / pmmpthread 等扩展，官方不发布
 * Linux ARM64 的 PHP 构建）。因此这里只提供真实可安装的版本，不虚构选项；
 * 将来上游若发布多版本，只需在枚举里追加一项并在 [PhpVersion.runtimeDirName]
 * 里给出对应目录即可，UI 与状态逻辑无需改动。
 */
@Serializable
enum class PhpVersion(
    val displayName: String,
    /** 上游发布 tag，用于拼接下载地址 */
    val releaseTag: String,
    /** 运行时安装目录名（相对 home/） */
    val runtimeDirName: String,
    /** 说明文案资源 id，null 表示不展示 */
    val noteRes: Int? = null
) {
    /**
     * PocketMine-MP 5 官方要求：PHP 8.2 + 内置 pmmp/ext-encoding 0.4.x。
     * 对应可兼容的最高 PocketMine 版本为 5.33.1（见 McServerController）。
     */
    Php82("PHP 8.2", "pm5-latest", "php-pmmp", com.mineserve.mobile.R.string.php_note_pmmp_required);

    /** 下载地址文件名（上游资产命名固定） */
    val tarballAsset: String get() = "php-android-$releaseTag.tar.gz"

    companion object {
        /** 默认（也是当前唯一）版本：PocketMine-MP 强制使用 */
        val Default: PhpVersion = Php82

        fun fromName(name: String?): PhpVersion? =
            entries.firstOrNull { it.name == name }
    }
}

@Serializable
enum class AutoBackupType(val displayName: String) {
    World("世界备份"),
    Server("完整服务器")
}

/**
 * Java 核心使用的 JVM 实现（由运行环境来源隐含决定，界面上不再暴露开关）。
 *
 * 两者跑的都是同一批已安装的 openjdk，差别只在**启动时挂不挂一层兼容库**：
 *
 *  - [TermuxJdk]：原样启动 Termux 的 openjdk。它判定「本机有没有 IPv6」靠
 *    `fopen("/proc/net/if_inet6")`，而 Android 上 App 读不到这个文件 ⇒ 判定为
 *    不支持 IPv6 ⇒ `ServerSocketChannel` 只按 IPv4 打开 ⇒ `server-ip=::`
 *    在 `Net.checkAddress` 抛 `UnsupportedAddressTypeException`，服务端启动即崩。
 *  - [PatchedJdk]：额外 `LD_PRELOAD` 我们的 [NativeLibraryBundler.IPV6_FIX_LIB]，
 *    把那次 fopen 重定向到非空文件，等价于 AOSP / Pojav 那条
 *    `skip_proc_net6_check` 编译期补丁 ⇒ JVM 认为 IPv6 可用 ⇒ `server-ip=::`
 *    可正常绑定（IPv6 与 IPv4 双栈监听）。
 *
 * 两种实现共用同一份 JAVA_HOME 与已装 JDK，**只影响 JVM 进程内的行为**：
 * [PatchedJdk] 除 IPv6 判定外不改动任何其他逻辑（Xmx、时区、TMPDIR、堆标签修复
 * 全都照旧）。Java 8（proot/Ubuntu 容器）用不到这层 —— guest 内是 glibc loader，
 * 加载不了这个 bionic 构建的库。
 */
@Serializable
enum class JvmFlavor {
    /** Termux 官方 openjdk，原样启动。 */
    TermuxJdk,
    /** 同一份 JDK + libipv6fix 兼容层（IPv6 监听可用）。 */
    PatchedJdk
}

@Serializable
enum class StepStatus { Done, Active, Wait }

@Serializable
data class StepState(val step: InstallStep, val status: StepStatus)

/**
 * 一个已安装到本地的服务端核心实例。
 * 每个核心有独立的文件夹（home/servers/{dirName}/），互不干扰。
 */
@Serializable
data class InstalledCore(
    /** 用户自定义名称（显示用），例如 "生存服-1.20.4" */
    val name: String,
    /** 核心类型 */
    val core: ServerCore,
    /** MC 版本 */
    val version: String,
    /** 文件夹名（从 name 自动生成，sanitized），例如 "sheng-cun-fu-1-20-4" */
    val dirName: String,
    /** 实际启动入口文件名；旧配置缺省为 server.jar，导入的完整目录可暂未指定。 */
    val serverFile: String? = "server.jar"
)

/**
 * 用户配置（持久化到 DataStore）
 */
@Serializable
data class McConfig(
    /** 下载页当前选择的核心类型（临时 UI 状态，不持久化） */
    val selectedCore: ServerCore = ServerCore.Paper,
    /** 下载页当前选择的 MC 版本（临时 UI 状态） */
    val mcVersion: String = "1.20.4",
    val coreSubDescription: String = "性能优化版，兼容大部分插件",
    /**
     * App 侧的本地端口（服务端端口的**镜像**）。
     *
     * 端口真源是**服务端配置文件**：Java 系为 `server.properties` 的 `server-port`，
     * PowerNukkitX 为 `pnx.yml` 的 `settings.port`。本字段不再独立代表「服务端监听的端口」，
     * 而是它的镜像，用于两类真源不可用的场景：
     * 1. 服务端首次启动前（配置文件尚未生成），启动前覆写需要写入的端口；
     * 2. 内网穿透的默认目标端口、MCP `get_config` 读出的值。
     *
     * 同步方向：
     * - 读：`loadServerProperties` 读到配置文件里的端口时回写本字段；
     * - 写：`saveServerProperties` 保存成功后把实际生效端口回写本字段
     *   （「服务器配置」页是端口唯一编辑入口，网络页的输入框已在 1.2.8 移除）；
     * - 切换核心：`selectCore` 仅在 Java 系 ↔ 基岩系之间对调**默认值**（见 [ServerPort]）。
     *
     * 界面展示（地址栏、复制地址、诊断）请读 `McViewModel.serverPort` 真源流，
     * 不要直接读本字段 —— 否则配置文件被外部改动时界面会停在旧值。
     */
    val localPort: Int = 25565,
    val customDomain: String = "myworld.mcserver.top",
    val tunnelType: TunnelType = TunnelType.Frp,
    /** frp: 完整 frpc.toml 配置文本（粘贴或文件导入） */
    val frpConfigText: String = "",
    /** bore: 服务端地址 (serverAddr:port) */
    val boreServerAddr: String = "",
    /** SakuraFrp: 访问令牌（api.natfrp.com） */
    val sakuraToken: String = "",
    /** SakuraFrp: 选中的隧道 ID（启动时据此拉取官方配置） */
    val sakuraTunnelId: String = "",
    /** ME Frp: 访问令牌（api.mefrp.com） */
    val meFrpToken: String = "",
    /** ME Frp: 选中的隧道（代理）ID（启动时据此拉取官方配置） */
    val meFrpTunnelId: String = "",
    /**
     * STUN: 转发目标本地端口；0 表示跟随 [localPort]（MC 服务端实际监听端口）。
     */
    val stunLocalPort: Int = 0,
    /** STUN: 并发转发连接数上限，超出的入站连接直接拒绝 */
    val stunMaxConnections: Int = 128,
    /** STUN: 保活探测间隔（秒），同时用于发现公网地址变化 */
    val stunKeepAliveSeconds: Int = 20,
    /**
     * STUN: 转发前是否发送 PROXY protocol v2 头。
     *
     * 开启后服务端才能看到玩家真实 IP（否则只有 127.0.0.1），但**服务端必须自行
     * 开启对应支持**（Paper/Velocity 等的 proxy_protocol 选项），否则这些字节会被
     * 当作协议数据，玩家直接进不来。故默认关闭。
     */
    val stunProxyProtocol: Boolean = false,
    /** STUN: 是否把每条入站连接的建立/断开写进隧道日志 */
    val stunShowConnectionLog: Boolean = true,
    /** STUN: 自定义 STUN 服务器（host 或 host:port）；留空使用内置列表 */
    val stunServers: String = "",
    val maxHeapMb: Int = 1024,            // -Xmx JVM 堆上限，按设备 RAM 给推荐值
    val autoRestartOnCrash: Boolean = false, // 默认关闭省电，避免误触发
    /**
     * 跳过「启动中」的阶段进度展示。
     *
     * 开启后概览页顶部不再显示启动阶段进度条与「正在启动服务端 · xxx」，
     * 而是直接按运行中呈现（TPS / 在线人数等服务端就绪后自然填上）。
     *
     * 只影响**界面呈现**，不改变启动链路的任何等待或探测 ——
     * 启动本身照旧逐步推进，[ServerState.startupPhase] 也照常更新，
     * 只是概览页不再把它摆在最显眼的位置。
     */
    val skipStartupProgress: Boolean = false,
    val selectedJavaVersion: JavaVersion = JavaVersion.Java17,
    /**
     * 当前选用的 Java **来源**（见 [JavaVendor]）。
     *
     * 与 [selectedJavaVersion] 一起构成完整的运行环境选择：版本号决定能力下限
     * （MC 版本要求的 Java 版本），来源决定用哪一套 JDK 实现。
     * 默认 [JavaVendor.Termux]，与历史行为一致（旧配置解析后自然落到这里）。
     */
    val selectedJavaVendor: JavaVendor = JavaVendor.Termux,
    /**
     * Termux 来源的 JDK 启动时是否挂 IPv6 兼容层（见 [JvmFlavor]）。
     *
     * 默认 [JvmFlavor.PatchedJdk] —— 也就是说 **Termux 来源默认就支持 `server-ip=::`**，
     * 用户不需要（也没有）任何开关去理解这件事。历史上这个开关曾在启动卡片上以
     * 「JVM 实现」两枚按键的形式暴露，容易让人以为默认那档是坏的；现在改为由
     * 来源分类隐含：Termux 自动挂，FCL 自带（不需要挂）。
     *
     * 字段本身保留，是为了给 MCP / 高级排查留一条「关掉兼容层做对照」的路。
     */
    val jvmFlavor: JvmFlavor = JvmFlavor.PatchedJdk,
    /**
     * 所选 PRoot 容器发行版（仅 [JavaVendor.Proot] 有意义）。
     *
     * 与 [selectedJavaVersion] 一起构成 PRoot 运行环境的完整坐标：
     * 「哪个发行版 + 里面装哪个 Java」。发行版决定**哪些 Java 版本真的能装**
     * （见 [ProotDistro.availableJavaVersions]），因此启动前必须校验这个组合，
     * 不能假设用户选的版本一定在该发行版里存在。
     *
     * 默认 [ProotDistro.Default]（jammy）。字段带默认值，老配置反序列化不受影响。
     */
    val prootDistro: ProotDistro = ProotDistro.Default,
    /**
     * 启动设置抽屉的运行模式（Java / PHP）。
     *
     * 仅作为"用户想看哪一类运行环境"的界面状态；真正决定用哪个运行时的是
     * 当前核心类型 —— PocketMine-MP 一律走 PHP，其他核心一律走 JVM。
     * 切换核心时会自动纠正该字段，避免出现 Java 模式配 PHP 核心的非法组合。
     */
    val runtimeKind: RuntimeKind = RuntimeKind.Java,
    /** 所选 PHP 运行时版本（PocketMine-MP 使用） */
    val selectedPhpVersion: PhpVersion = PhpVersion.Default,
    /** 用户手动将 Java 管理卡片固定到概览页底部。 */
    val javaCardAtBottom: Boolean = false,
    val keepWifiLock: Boolean = true,
    val keepCpuWakelock: Boolean = true,
    /** 服务端运行且主界面可见时保持屏幕常亮。 */
    val keepScreenOnWhileRunning: Boolean = false,
    /** 服务端运行时显示可拖动的状态悬浮窗。 */
    val keepStatusOverlay: Boolean = false,
    /** 自动备份间隔（分钟），0 表示关闭 */
    val autoBackupIntervalMin: Int = 0,
    /** 自动备份内容，默认仅备份世界以避免无意生成大型 ZIP。 */
    val autoBackupType: AutoBackupType = AutoBackupType.World,
    /** 保留的最大快照数量，超过则自动删除最旧的 */
    val maxSnapshots: Int = 10,
    /** Termux 环境/依赖下载源，默认镜像优先 */
    val downloadMirror: DownloadMirror = DownloadMirror.Auto,
    /** Termux apt 软件源（JDK/wget/frp 依赖包），默认清华镜像 */
    val aptMirror: AptMirror = AptMirror.Tuna,
    /** 已安装到本地的服务端核心列表（多核心支持） */
    val installedCores: List<InstalledCore> = emptyList(),
    /** 当前选用启动的核心名称（对应 InstalledCore.name），null 表示未选择 */
    val activeCoreName: String? = null,
    // 注：旧版兼容字段 downloadedCore / downloadedVersion 已删除。它们由 installedCores
    // 取代且无任何读写方，旧配置文件里的残留键由 Json(ignoreUnknownKeys = true) 自动忽略。
    // ── 自定义启动命令（启动设置抽屉） ─────────────────────────
    /** 完全自定义启动命令开关：开启后直接执行整条命令，忽略自动拼接 */
    val advancedCustomCommandEnabled: Boolean = false,
    /** 完全自定义启动命令（整条 Java 启动指令） */
    val advancedCustomCommand: String = "",
    // 注：原「模组兼容模式」开关（moduleCompatMode）已删除。那组缓解 OSHI/JNA 崩服的
    // JVM 属性改为无条件注入（见 TermuxRuntime.baseJvmProperties），用户无需再判断
    // 何时开启。旧配置文件里的残留键由 Json(ignoreUnknownKeys = true) 自动忽略。
    // ── 定时任务与停止备份 ────────────────────────────────────
    /** 每日定时开服开关 */
    val dailyStartEnabled: Boolean = false,
    /** 每日定时开服时间（小时，0-23） */
    val dailyStartHour: Int = 8,
    /** 每日定时开服时间（分钟，0-59） */
    val dailyStartMinute: Int = 0,
    /** 每日定时关服开关 */
    val dailyStopEnabled: Boolean = false,
    /** 每日定时关服时间（小时，0-23） */
    val dailyStopHour: Int = 23,
    /** 每日定时关服时间（分钟，0-59） */
    val dailyStopMinute: Int = 0,
    /** 服务器停止后自动备份一份世界快照（服从 maxSnapshots 保留策略） */
    val stopAutoBackup: Boolean = false,
    // ── MCP（Model Context Protocol）内嵌服务器 ──────────────
    /** 启用内嵌 MCP 服务器，局域网内 AI 助手可通过 HTTP 管理 MC 服务器 */
    val mcpEnabled: Boolean = false,
    /** MCP HTTP 监听端口 */
    val mcpPort: Int = 8931,
    /** MCP 访问令牌（Bearer 鉴权），首次启用时自动生成 */
    val mcpToken: String = "",
    /**
     * 是否允许局域网访问 MCP 端口；false 时只监听 127.0.0.1（默认，更安全）。
     *
     * 默认关闭是有意的：MCP 端点可执行 run_termux_command 等特权操作，
     * 而局域网（尤其是公共 Wi-Fi）并不总是可信，旧版本无条件绑 0.0.0.0
     * 等于把端口暴露给同网段任意主机。需要手机以外的 AI 助手接入时，
     * 用户须显式开启。字段带默认值，老配置反序列化不受影响。
     */
    val mcpAllowLan: Boolean = false
) {
    /**
     * 当前选用的运行环境 = 来源 + 版本。
     *
     * 只在需要「区分来源」的地方用它；纯粹的版本比较（比如按 MC 版本推荐 Java）
     * 仍然用 [selectedJavaVersion]，因为那是版本能力问题、与来源无关。
     */
    val selectedJavaRuntime: JavaRuntime
        get() = JavaRuntime(selectedJavaVendor, selectedJavaVersion)

    /**
     * 当前来源的 JDK 是否支持 IPv6 监听。
     *
     * 三种来源各不相同：
     * - [JavaVendor.Fcl]：那套 JRE 的 `libnet.so` 编译期就跳过了 IPv6 接口检查，自带支持。
     * - [JavaVendor.Termux]：bionic JDK 本身判定「本机无 IPv6」，
     *   要靠 `libipv6fix`（[JvmFlavor.PatchedJdk]）这个 `LD_PRELOAD` 兼容层绕过。
     * - [JavaVendor.Proot]：来宾里是 glibc loader，加载不了按 bionic 构建的兼容层。
     *
     * ## Termux + Java 8 必须先判容器
     *
     * 「Termux + Java 8」其实跑在 proot 容器里（与 `PRoot + focal` 是同一个容器），
     * 因此它也属于 glibc 世界、同样挂不上兼容层。
     * 早先这里只按 `jvmFlavor` 判断，于是用户选了 Termux + Java 8 + PatchedJdk 时
     * 会得到 `true` —— 而实际启动时兼容层根本没被加载，结果是
     * Allay 被写成 `enablev6: true`，服务端起来时抛
     * `UnsupportedAddressTypeException`。判定必须与真实链路一致。
     */
    val ipv6ListenAvailable: Boolean
        get() {
            // 先判「是否走来宾容器」：容器内一律不支持，与 jvmFlavor 无关
            if (isProotGuestRuntime(selectedJavaVendor, selectedJavaVersion)) return false
            return when (selectedJavaVendor) {
                JavaVendor.Fcl -> true
                JavaVendor.Termux -> jvmFlavor == JvmFlavor.PatchedJdk
                // 来宾里是 glibc 的 java，兼容层是按 bionic 构建的 LD_PRELOAD 库，
                // 在 guest 内根本加载不了 ⇒ 与 Termux 不挂兼容层时同样不支持 `server-ip=::`。
                // 如实返回 false，让 UI 的提示与启动日志一致，而不是给出一个乐观的谎。
                JavaVendor.Proot -> false
            }
        }

    /**
     * 当前 PRoot 选择对应的容器画像（仅 [JavaVendor.Proot] 有意义）。
     *
     * @return 组合合法时返回画像；所选发行版**不提供**该 Java 版本时返回 null。
     *   调用方（启动校验、UI、诊断）拿到 null 必须给出明确提示，
     *   而不是静默换一个版本 —— 用户选的和他得到的必须是同一个东西。
     */
    val prootProfile: ProotContainerProfile?
        get() = prootDistro.profileFor(selectedJavaVersion)
}

/**
 * 服务器实时状态（运行时内存态，由 Service 推送）
 */
data class ServerState(
    val isRunning: Boolean = false,
    val tps: Double = 0.0,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 20,
    val usedMemoryMb: Long = 0L,
    /** 服务端进程 CPU 占用率（%），按可用核心数归一化到 0-100；未运行时为 null。 */
    val cpuPercent: Int? = null,
    val maxMemoryMb: Long = 0L,
    val healthPercent: Int = 0,            // 0-100，综合健康度
    /** 服务器本次启动完成的时刻（SystemClock.elapsedRealtime 基准），0 表示未启动 */
    val runningSinceMs: Long = 0L,
    /** 启动过程阶段，仅保存在运行时状态，不写入配置。 */
    val startupPhase: StartupPhase = StartupPhase.Idle,
    val installSteps: List<StepState> = InstallStep.values().map {
        StepState(it, StepStatus.Wait)
    },
    val currentProgress: Int = 0           // 0-100 安装进度
) {
    val isInstallComplete: Boolean get() = installSteps.filter { it.step != InstallStep.Jdk }.isNotEmpty() &&
        installSteps.filter { it.step != InstallStep.Jdk }.all { it.status == StepStatus.Done }
}

enum class StartupPhase(val label: String, val progress: Float) {
    Idle("未启动", 0f),
    PreparingEnvironment("准备环境", 0.07f),
    StartingJava("启动 Java", 0.20f),
    DownloadingDependencies("下载依赖", 0.40f),
    LoadingCore("加载核心", 0.62f),
    /**
     * 网络监听。
     *
     * 进度**低于**"创建世界"是刻意的：真实日志顺序是
     * `Starting Minecraft server on *:25565`（绑定端口）
     * **先于** `Preparing level "world"`（创建世界）。
     * 若这里给更高的值，进度条会先冲到 92% 再跌回 80%，
     * 出现可见的回退跳动。
     */
    StartingNetwork("启动网络", 0.72f),
    CreatingWorld("创建世界", 0.82f),
    Ready("已完成", 1f),
    Failed("启动失败", 0f)
}

/**
 * 从一行服务端日志推断当前启动阶段。
 *
 * ## 判定顺序 = 优先级（严格按 progress 从高到低）
 * `when` 是短路求值，所以**先匹配到的赢**。分支顺序必须与
 * [StartupPhase.progress] 的降序一致，否则一条同时含多个关键词的行
 * 会被误判到更早的阶段，进度条就会"卡住不动"。
 *
 * ## 关键词必须覆盖服务端的**真实输出**
 * 这是本函数最容易出错的地方。有两类中文日志都要认：
 *
 * ### 1. 服务端自己的中文输出
 * Minexa 打包的服务端（Fabric/Forge 等）在中文环境下会输出：
 * ```
 * 正在加载 Minecraft server
 * 正在加载 required files
 * 正在加载 library org.ow2.asm:asm:9.10.1
 * 正在启动 net.fabricmc.loader.impl.game.minecraft.BundlerClassPathCapture
 * ```
 * 而早期关键词几乎全是英文（`libraries`、`loading minecraft`），
 * 结果是**大段日志一行都识别不出来**：
 *   - `library` 是**单数**，而代码里只写了 `libraries`
 *   - `正在加载 Minecraft` 是中文，`loading minecraft` 匹配不到
 *
 * ### 2. 终端日志汉化插件的译文
 * 用户开启"终端内日志汉化"后，**服务端与用户的日志全部变成中文**：
 * ```
 * 正在下载依赖库 …                        （原 Downloading library …）
 * 正在正在服务器上安装 Fabric 加载器 …      （原 Installing Fabric Loader …）
 * 完成（0.924秒）！如需帮助，请输入 "help"   （原 Done (0.924s)! …）
 * ```
 * 这类译文的关键词**不能凭空猜**，必须按用户实际贴出的日志原文补
 * （曾两次因为猜测而翻车）。补词时优先用 `正在加载 ` 这类
 * **精确到含空格**的短语做兜底，而不是 `加载`、`mixin` 这类过宽的词。
 *
 * 所以中英文关键词必须**成对维护**：每加一个英文词，就要确认它的
 * 中文对应写法（服务端原生 + 汉化译文两种）是否也存在。
 *
 * 表现为进度条长期停在某一档不动，然后又突然跳到很后面的阶段
 * （"阶段被跳过" + "整体都不对"）。
 *
 * ## 关键词要够窄，否则会误伤
 * 曾经有过 `text.contains("jvm")`，`jvm` 出现在太多无关行里
 * （JVM 参数回显、`[startmc]` 环境信息、mod 的 JVM 检测输出……），
 * 结果进度被反复钉死在 `StartingJava`(20%)。
 * 也曾有过 `mixin` / `remapping`，这两个词在资源重载、类编译、报错、
 * 插件配置路径里都会出现，误伤面极大。
 * **过宽的词一律不要**，只认明确表达该动作的短语。
 */
fun startupPhaseForLog(line: String): StartupPhase? {
    val text = line.lowercase()
    // 异常堆栈行不参与阶段判定，见 [isStackFrameLine]。
    if (isStackFrameLine(line)) return null
    return when {
        // ── 就绪（最高优先级：出现即完成） ────────────────
        //
        // ⚠️ 这一档的每个词都必须**限定上下文**，否则会把前面阶段的日志
        // 误判成"已完成"，进度条直接跳 100%，后面所有阶段全被吞掉。
        // 踩过的两个真实坑（都由单元测试挡住）：
        //   - `已就绪`：`[bootstrap] Java 25 wrapper 脚本已创建完成: 6 个命令已就绪`
        //     含"已就绪"，会在**启动第 2 秒**就判成就绪。
        //   - `完成(` ：同一行的"已创建完成:"后面正好跟冒号，被 `完成` 前缀匹配。
        // 所以只认能明确表达"服务端启动完毕"的整句写法。
        text.contains("启动完成") || text.contains("服务端已启动完成") ||
            text.contains("启动完毕") || text.contains("服务端已就绪") ||
            text.contains("服务端就绪") ||
            text.contains("server started") ||
            text.contains("done (") || text.contains("done!") ||
            // 汉化插件会把 "Done (2.483s)!" 翻成 "完成（2.483秒）！"
            // 注意必须是**全角左括号**紧跟"完成"，不能只写"完成"
            text.contains("完成（") ||
            text.contains("enabled bungeecord") || text.contains("velocity has started") ||
            // Allay：网络接口启动完成即为就绪（空闲期不再输出日志）
            text.contains("network interface started at") ||
            text.contains("网络接口已启动于") -> StartupPhase.Ready

        // ── 创建世界 ──────────────────────────────────
        // 排在网络之前：进度 0.82 > 0.72，`when` 短路要求高进度在前。
        text.contains("preparing level") || text.contains("preparing start region") ||
            text.contains("preparing spawn") || text.contains("preparing world") ||
            text.contains("preparing spawn area") || text.contains("加载世界") ||
            text.contains("正在加载世界") || text.contains("正在准备出生点区域") ||
            text.contains("创建世界") ||
            // 汉化插件对应的中文写法
            text.contains("选择全局世界出生点") ||
            text.contains("正在准备世界") ||
            // "Loading 0 persistent chunks..." 的译文。
            // 注意必须写成"正在加载 %d 个持久化区块"，不能只写"持久化区块"：
            // 后者会被上面核心档的 `正在加载 ` 抢走
            text.contains("个持久化区块") -> StartupPhase.CreatingWorld

        // ── 网络监听 ──────────────────────────────────
        // 排在创建世界**之后**：两者进度分别是 0.72 / 0.82，
        // `when` 短路要求高进度在前，否则顺序与 progress 不一致。
        text.contains("listening on") || text.contains("starting minecraft server on") ||
            // 中文日志："正在启动 Minecraft 服务端" 紧随 "Starting Minecraft server on" 出现。
            //
            // ⚠️ 但 "Starting minecraft server **version** 26.3" 的译文中也含
            // "正在启动 minecraft 服务端"（即 "正在启动 Minecraft 服务端版本 26.3"），
            // 而那句属于**加载核心**（原始英文含 "version"，靠核心档的
            // `starting minecraft server version` 命中）。这里显式排除掉，
            // 否则核心档那句会被网络档抢先，进度条提前跳到 72%。
            text.contains("正在启动 minecraft 服务端") && !text.contains("版本") ||
            // 汉化插件译文（注意是"世界"不是"服务端"）
            text.contains("正在启动 minecraft 世界") ||
            text.contains("启动 gs4") || text.contains("query 运行") ||
            text.contains("server bound") || text.contains("监听") -> StartupPhase.StartingNetwork

        // ── 下载依赖 ──────────────────────────────────
        // 必须排在 LoadingCore 之前：下载阶段的日志会**夹带**核心加载的字样。
        // 例如 "正在加载 Minecraft server" 同时含 "server" 与 "minecraft"，
        // 若核心分支在前，这行会被判成"加载核心"，整段下载都被跳过。
        // "Installing Fabric Loader …" 同理（含 "fabric loader"）。
        text.contains("[download]") || text.contains("下载依赖") ||
            text.contains("安装依赖") || text.contains("依赖包") ||
            text.contains("downloading") || text.contains("downloaded") ||
            // library 与 libraries 都要认：服务端实际输出的是**单数** library
            text.contains("library") || text.contains("libraries") ||
            text.contains("mojang_") ||
            // 中文下载日志
            text.contains("正在下载") || text.contains("下载 ") ||
            text.contains("安装 ") ||
            text.contains("所需文件") ||
            text.contains("正在解包") || text.contains("解压 ") ||
            text.contains("生成服务端启动") || text.contains("服务器启动 jar") ||
            text.contains("required files") || text.contains("unpacking") ||
            text.contains("generating server launch jar") ||
            text.contains("server launch jar") ||
            // 引导阶段：装核心 / 装加载器 / 展开服务端 jar
            text.contains("正在加载 minecraft server") ||
            text.contains("installing ") || text.contains("installing fabric") ||
            text.contains("正在服务器上安装") ||
            // ⚠️ 启动器的"环境探测"行：下载阶段会先打
            //   "Loading Minecraft 26.3 with Fabric Loader…"（译文
            //   "正在加载 Minecraft 26.3 及依赖"），探完**才继续下载**。
            // 若把它算成"加载核心"(62%)，在"只增不减"下后面真正的下载行
            // (40%) 会被整段忽略，进度条卡在 62% 不动 —— 用户看到的正是
            // "下载和加载核心两个左右跳"。
            // 判定依据：这句带"及依赖 / with … loader"这类**环境描述**，
            // 而真正的核心加载是"正在加载 Minecraft 服务端 / mods / 配置"。
            (text.contains("正在加载 minecraft") && text.contains("依赖")) ||
            (text.contains("loading minecraft") && text.contains("with")) ||
            // ── 模糊行下沉（见"加载核心"档顶部的说明）──────────────
            // 只下沉**确实可能出现在下载阶段**的行。
            // ⚠️ 下面这些**不下沉**，保留在核心档，因为它们都是核心加载的锚点：
            //     - `个模组`：模组列表只在核心真正加载时打印
            //     - `缺少映射 / mappings not present`：这是 Fabric 核心加载器
            //       启动时打印的，两侧都是"正在加载 Minecraft"/"正在加载 N 个模组"。
            //       实测把它下沉会造成 62% → 40% → 62% 的一次抖动，
            //       用户看到的就是"两个左右跳"。
            text.contains("正在替换旧版本") || text.contains("正在修复") -> StartupPhase.DownloadingDependencies

        // ── 加载核心 ──────────────────────────────────
        //
        // ⚠️⚠️ 模糊行一律下沉到"下载依赖"档 ⚠️⚠️
        //
        // `Mappings not present!` / `Loading 4 mods:` 这类行在 Fabric 启动器里
        // **既可能出现在下载阶段（环境探测），也可能出现在真正的核心加载阶段**，
        // 单看这一行无法区分。
        //
        // 遇到这种模糊时**必须选低档（下载 40%）**，不能选高档（核心 62%）：
        //   - 选低档：只可能让进度条多停在 40% 一会儿 → 用户能接受
        //   - 选高档：后面真正的下载行（也是 40%）会被"只增不减"全部忽略，
        //             进度条**彻底卡死**在 62%，直到"启动网络"才动 → 就是
        //             用户反馈的"两个左右跳 / 卡着不动"
        //
        // 所以下面这些行统一由前面的"下载依赖"分支接走（见那里的"模糊行下沉"段），
        // 这里只保留**无歧义**的核心加载证据。
        text.contains("loading server") || text.contains("loading properties") ||
            text.contains("loading nukkit") || text.contains("loading plugins") ||
            text.contains("loading minecraft") || text.contains("mod loading") ||
            text.contains("modlauncher") || text.contains("quilt loader") ||
            text.contains("fabric loader") || text.contains("booting up velocity") ||
            text.contains("starting bungeecord") || text.contains("正在启动 minecraft") ||
            // "Starting net.fabricmc.loader…BundlerClassPathCapture" 的译文。
            // 这句话里没有 "minecraft"，所以必须单独列出（见"启动 Java"档的注释）
            text.contains("正在启动 net.fabricmc") ||
            text.contains("starting org.bukkit") ||
            // 中文核心加载日志（关键补充：这些行以前一行都识别不出来）
            text.contains("正在加载 minecraft") || text.contains("正在加载服务端配置") ||
            text.contains("正在加载 mods") || text.contains("mods:") ||
            // "Loading libraries..." 的译文是"正在加载库 …" → "库"
            // 注意不能用裸"库"（太宽），用"正在加载库 "带空格限定
            text.contains("正在加载库 ") ||
            // "正在加载配置" == "Loading properties" 的译文
            text.contains("正在加载配置") ||
            // 汉化插件译文：其余每条都以"正在加载 "开头（注意含空格）。
            // 上面那些特定写法先被命中，所以这条通配不会误伤；
            // 而"正在加载 library …"（带引号的下载行）在更前面被下载分支接走。
            // 这**不会**影响 "[bootstrap] … 已创建完成"：
            // 那里是"已创建"，不含"正在加载 "，故仍归准备环境。
            text.contains("正在加载 ") ||
            text.contains("starting minecraft server version") ||
            text.contains("generating keypair") || text.contains("no existing world data") ||
            text.contains("mappings not present") ||
            // 汉化译文
            text.contains("没有现有的世界数据") || text.contains("缺少映射") ||
            text.contains("正在生成密钥对") -> StartupPhase.LoadingCore

        // ── 启动 Java（关键词收窄，不再匹配裸 "jvm"） ──────
        text.contains("[startmc] java 路径") || text.contains("正在启动 java") ||
            text.contains("starting java") ||
            // ⚠️ 不要把 "正在启动 net.fabricmc.loader…" 放这里。
            // 它是 "Starting net.fabricmc.loader…BundlerClassPathCapture" 的译文，
            // 而那句属于**加载核心**（类加载器已接手，Java 进程早起来了）。
            // 用户真实日志里它正是「加载核心」段的第一行；若归到这一档(20%)，
            // 进度条会从 62% 掉回 20%。核心档的 `正在启动 minecraft` 已覆盖它。
            // 只认带上下文的版本声明，避免命中任意含 "jvm" 的日志。
            // 汉化插件会把 "version" 翻成"版本"，所以 `openjdk 版本` 也要认。
            text.contains("openjdk version") || text.contains("openjdk 版本") ||
            text.contains("java version") || text.contains("java 版本") ||
            text.contains("java hotspot") -> StartupPhase.StartingJava

        // ── 准备环境（最早期，进度最低，放最后） ──────────
        // App 自身的引导日志走这里。以前**没有任何关键词覆盖这一段**，
        // 于是启动的头几秒进度条停在初始值不动（表现为"启动卡住"）。
        //
        // 位置说明：`when` 是短路求值，**前面的分支优先**。这一档 progress 最低
        // （0.07），所以必须放在所有其他阶段之后，否则会抢走更靠后阶段的日志。
        // 例如 "[startMc] java 路径: ..." 含 "[startmc]" 但不含 [bootstrap]，
        // 两档不冲突；但任何同时命中的行都会先被判为 Java 启动 —— 这是对的，
        // 因为出现 java 路径就说明环境准备已经过了。
        text.contains("[bootstrap]") || text.contains("[repair]") ||
            text.contains("[jvm]") || text.contains("[proot]") ||
            text.contains("修复") && text.contains("脚本路径") ||
            text.contains("wrapper 脚本") || text.contains("脚本已创建") ||
            text.contains("正在检查环境") || text.contains("准备环境") -> StartupPhase.PreparingEnvironment

        else -> null
    }
}

/**
 * 判断一行是不是 **Java 异常堆栈帧**（属于堆栈输出，而非服务端的状态播报）。
 *
 * ## 为什么必须把堆栈排除掉
 * 堆栈里的类名/包名会**回显**先前阶段的关键字，而且是在很久之后才回显。
 * 实测最典型的一条（来自用户的真实日志）：
 * ```
 * at net.fabricmc.installer.ServerLauncher.main(ServerLauncher.java:69)
 * ```
 * 含 `server`，被判成"下载依赖"(40%)；而它出现在**加载核心阶段之后**，
 * 于是进度条从 62% 掉回 40%。同类还有
 * ```
 * at java.base/jdk.internal.loader.NativeLibraries$NativeLibraryImpl.open(...)   → 含 "libraries"
 * at knot//net.minecraft.SystemReport.putHardware(SystemReport.java:105)         → 含 "server"
 * ```
 * 这些行**一个都不该**推动进度。
 *
 * ## 如何识别
 * 只在行首附近出现 `at `（含 `at java.base/`、`at knot//` 这类 JDK 与
 * 加载器前缀）才判为堆栈帧。刻意做得保守：
 *   - 只认行首的 `at`，不认正文里出现的 "at"，否则会误伤正常日志
 *   - 不把 `Caused by:` / `... 12 more` 也一起拦掉太激进，它们本来就不含
 *     阶段关键词，靠关键词表即可自然过滤
 *
 * 这样 App 自己输出的 `[startMc] … at …` 之类正常行不受影响。
 */
internal fun isStackFrameLine(line: String): Boolean {
    val t = line.trimStart()
    if (!t.startsWith("at ")) return false
    // 排除误伤：真正的堆栈帧至少形如 "at xxx.yyy(" 或 "at xxx.yyy.zzz("
    return t.contains('(') || t.contains('.')
}
