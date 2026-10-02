package com.mineserve.mobile.data

import java.io.File

/**
 * 「某个发行版里的某个 Java 版本」——PRoot 运行环境的完整坐标。
 *
 * 这是 [ProotDistro] 与 [JavaVersion] 的合法组合，构造时即保证**可安装**
 * （只能经 [ProotDistro.profileFor] 得到），因此调用方拿到它就等于
 * 「这个组合在容器里是装的上的」，不需要再自己校验。
 *
 * ## 路径推导集中在这里的原因
 *
 * 以前这些路径散在 `TermuxRuntime` 的多个方法里各写一遍（rootfs、标记、
 * JAVA_HOME、libjvm），改一个漏一个就会导致「显示已安装但启动找不到 java」。
 * 现在统一由本类推导，`TermuxRuntime` 只负责调用。
 */
data class ProotContainerProfile(
    val distro: ProotDistro,
    val version: JavaVersion
) {
    init {
        // 构造即校验：只有 ProotDistro.profileFor 能造出合法组合，
        // 若这里断言失败，说明有人绕过它手工拼了一个装不上的组合。
        require(version in distro.availableJavaVersions) {
            "${distro.displayName} 不提供 ${version.displayName}，不能构造容器画像"
        }
    }

    /**
     * 来宾内的 JAVA_HOME。
     *
     * Ubuntu 的多架构 JDK 固定落在 `<pkg>-arm64` 目录，例如
     * `Java8` → `/usr/lib/jvm/java-8-openjdk-arm64`。
     * 路径完全由版本号推导，因此新增发行版或新增 Java 版本都不需要改这里。
     */
    val javaHomeInGuest: String
        get() = "/usr/lib/jvm/java-${version.name.removePrefix("Java")}-openjdk-arm64"

    /** 来宾内 `java` 可执行文件路径（新 layout）。 */
    val javaBinaryInGuest: String get() = "$javaHomeInGuest/bin/java"

    /**
     * 来宾内 `java` 的所有可能位置，按优先级排列。
     *
     * Java 8 的 Ubuntu 包用的是老 layout（JRE 在 `jre/` 子目录下），
     * 11 及以上是新 layout。少写一条就会让其中一半版本被误判成「未安装」，
     * 而这个误判在界面上只表现为「环境装好了却显示未安装」，极难排查。
     */
    val guestJavaBinaryCandidates: List<String>
        get() = listOf(
            "$javaHomeInGuest/bin/java",
            "$javaHomeInGuest/jre/bin/java"
        )

    /** 来宾内 `libjvm.so` 的所有可能位置（老 layout 在 `jre/lib/aarch64/` 下）。 */
    val guestJvmLibraryCandidates: List<String>
        get() = listOf(
            "$javaHomeInGuest/lib/server/libjvm.so",
            "$javaHomeInGuest/jre/lib/aarch64/server/libjvm.so"
        )

    /** 本组合在容器内安装时需要的 apt 包名。 */
    val aptPackage: String
        get() = when (version) {
            JavaVersion.Java8 -> "openjdk-8-jdk"
            JavaVersion.Java11 -> "openjdk-11-jdk"
            JavaVersion.Java17 -> "openjdk-17-jdk"
            JavaVersion.Java21 -> "openjdk-21-jdk"
            // Java 25 不在任何首期发行版的 availableJavaVersions 里，
            // 构造期（见 init）就已拦截，正常流程走不到这里。
            JavaVersion.Java25 -> "openjdk-25-jdk"
        }

    /** 用来给用户看的一句话：`Ubuntu 22.04 (jammy) · Java 17`。 */
    val displayName: String get() = "${distro.displayName} · ${version.displayName}"

    // ── 宿主侧路径（全部相对 $PREFIX = filesDir/home） ──────────

    /** 容器 rootfs 根目录。 */
    fun rootfsDir(prefix: File): File = File(prefix, "var/lib/mineserve/${distro.dirName}")

    /**
     * 就绪标记文件。
     *
     * 与 rootfs 同级同名（`<dirName>.ready`），这样「装了哪个发行版」
     * 一眼能从文件名看出来，且新增发行版无需改任何判断。
     *
     * 标记的是**该发行版容器本身已装好**（rootfs + apt 源可用），
     * 而不是「里面装了哪个 Java」—— 同一个 rootfs 可以装多个 JDK，
     * 具体版本是否就绪由容器内实测 [ProotContainerProfile.guestJavaBinaryCandidates] 决定。
     */
    fun readyMarker(prefix: File): File =
        File(prefix, "var/lib/mineserve/${distro.dirName}.ready")

    /**
     * PRoot 的 glue 文件目录（`PROOT_TMP_DIR`）。
     *
     * 必须留在**应用自己的前缀**内：PRoot 会在 rootfs 之外创建胶水文件，
     * 而 Termux 的兼容路径 `/usr/tmp` 在新版 Android 存储命名空间下可能不存在或不可写。
     * 按发行版分开，避免两个容器并发启动时互相踩到对方的 glue 文件。
     */
    fun prootTmpDir(prefix: File): File = File(prefix, "tmp/proot-${distro.dirName}")

    /** 来宾 `/tmp` 的宿主落点（作为 `/dev/shm` 绑定进去）。 */
    fun guestTmpDir(prefix: File): File = File(rootfsDir(prefix), "tmp")

    /**
     * 本发行版的 resolv.conf 宿主文件。
     *
     * 按发行版分开，避免两个容器抢同一个文件。
     */
    fun hostResolvConf(tmpDir: File): File = File(tmpDir, "${distro.dirName}-resolv.conf")

    companion object {
        /** 首期所有可用的「发行版 × Java 版本」组合（已过滤掉装不上的）。 */
        val all: List<ProotContainerProfile>
            get() = ProotDistro.entries.flatMap { distro ->
                distro.availableJavaVersions.map { ProotContainerProfile(distro, it) }
            }

        /** 按 (发行版, 版本) 查找；组合不合法或版本不可装时返回 null。 */
        fun find(distro: ProotDistro, version: JavaVersion): ProotContainerProfile? =
            distro.profileFor(version)
    }
}

/**
 * 某个 (来源, 版本) 组合是否跑在 PRoot 来宾里。
 *
 * 同时接受历史上的「Termux 来源 + Java 8」写法：那条组合在 PRoot 通用化之前
 * 就走容器链路，属于**必须保持兼容**的既有语义。
 *
 * 启动分派、来宾命令判定、Forge 安装路径三处都从这里派生，
 * 避免各自写一份条件导致行为不一致（那正是「换个入口行为就变了」的典型来源）。
 */
fun isProotGuestRuntime(vendor: JavaVendor, version: JavaVersion): Boolean =
    when (vendor) {
        JavaVendor.Proot -> true
        JavaVendor.Termux -> version == JavaVersion.Java8
        JavaVendor.Fcl -> false
    }

/**
 * 「Termux + Java 8」实际使用的发行版。
 *
 * 该组合在容器通用化之前就一直跑在 focal 里，且**没有**发行版可选。
 * 必须与 `TermuxRuntime.legacyJava8Profile` 指向同一个容器，否则老配置会
 * 被判成「未安装」到两个不同的 rootfs 之间来回跳。
 */
val LegacyJava8Distro: ProotDistro = ProotDistro.Focal

/**
 * 把历史配置归一到当前模型。
 *
 * 只处理一种历史形态：`Termux + Java 8`。Java 8 现在只出现在 Proot 分组
 * （它本来就跑在容器里，挂在 Termux 下会与 Proot 分组重复列同一个容器），
 * 因此这里把它改写为它实际所属的 `Proot + Java 8`，并把 [McConfig.prootDistro]
 * 对齐到 focal —— 该字段是后加的、老配置里只有默认值 jammy，
 * 而 jammy **不提供 Java 8**。
 *
 * 不做这步会怎样：老用户打开启动抽屉会看到「PRoot Linux 容器」被选中、
 * 发行版显示 jammy、而版本一栏**空白**（选中项不在候选列表里）。
 * 归一后三个字段自洽，界面侧不再需要任何「历史写法」特例分支，
 * 而实际运行的容器与归一前完全一致（都是 focal + Java 8），
 * 既不影响已装环境，也不需要用户重新安装。
 */
fun McConfig.normalizeProotRuntime(): McConfig {
    if (selectedJavaVendor != JavaVendor.Termux) return this
    if (selectedJavaVersion != JavaVersion.Java8) return this
    return copy(selectedJavaVendor = JavaVendor.Proot, prootDistro = LegacyJava8Distro)
}
