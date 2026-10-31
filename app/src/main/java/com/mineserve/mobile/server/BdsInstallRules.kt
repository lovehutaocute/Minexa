package com.mineserve.mobile.server

import java.io.File

/**
 * BDS 核心替换时的「哪些文件可以动、哪些绝不能动」规则。
 *
 * 抽成纯文件操作单独一层，是因为这几条规则错了就是不可逆的毁数据
 * （世界被清空、白名单被重置、端口被改回默认），必须能被单测直接锁住 ——
 * 而完整的更新流程要下 93MB 包、要跑 termux 环境，没法在单测里跑。
 *
 * 官方安装包的实际内容（1.26.61.1 实测，共 1392 个条目）：
 * ```
 * bedrock_server            265MB  可执行文件
 * bedrock_server_how_to.html
 * server.properties        15.6KB  空默认模板（端口默认 19132、白名单关）
 * allowlist.json               3B  "[]"
 * permissions.json             3B  "[]"
 * packetlimitconfig.json / profanity_filter.wlist / release-notes.txt
 * behavior_packs/ resource_packs/ definitions/ data/ config/
 * ```
 * 注意包里**没有** `worlds/`；而 `server.properties`、`allowlist.json`、
 * `permissions.json` 都带着空默认值，直接覆盖 = 清空用户设置。
 */
internal object BdsInstallRules {

    /** 更新时绝不覆盖的用户数据文件（目标已存在就跳过） */
    val PRESERVED_FILES = setOf(
        BdsSupport.PROPERTIES_NAME,
        "allowlist.json",
        "permissions.json"
    )

    /** 更新时整棵子树都不碰的目录 */
    val PRESERVED_DIRS = setOf("worlds")

    /**
     * 把 [from] 的内容合并进 [to]：官方文件覆盖，用户文件保留。
     *
     * 三条规则，缺一条就会毁用户数据：
     *  - [PRESERVED_FILES] 中目标已存在的**跳过**（`server.properties` 在官方包里是
     *    16KB 的默认模板，覆盖等于把端口、白名单开关、正版验证全重置）；
     *  - [PRESERVED_DIRS] 整棵子树不碰（`worlds/` 必须原样保留）；
     *  - 只写不删：用户自己放进 `behavior_packs/` 的包，新版本安装包里没有，
     *    但绝不能因为「新包里没有」就删掉。
     *
     * @param written 记录**本次新建**的文件路径，回滚时按它删除
     */
    fun installTree(from: File, to: File, written: MutableList<File>) {
        val children = from.listFiles() ?: return
        for (src in children) {
            val dest = File(to, src.name)
            if (src.isDirectory) {
                if (src.name in PRESERVED_DIRS) {
                    // 官方包将来若真的带上 worlds/，也只补用户没有的那部分
                    if (!dest.exists()) {
                        dest.mkdirs()
                        installTree(src, dest, written)
                    }
                    continue
                }
                dest.mkdirs()
                installTree(src, dest, written)
                continue
            }
            if (src.name in PRESERVED_FILES && dest.exists()) continue
            val existed = dest.exists()
            src.copyTo(dest, overwrite = true)
            if (!existed) written += dest
            if (src.name == BdsSupport.ENTRY_NAME) BdsSupport.makeExecutable(dest)
        }
    }

    /**
     * 只保留最新一份旧核心：[serverDir] 里 `bedrock_server.old-*` 每个 265MB，
     * 留着历史版本会吃掉几 GB。回滚只可能用到最近的那一份。
     */
    fun pruneOldEntries(serverDir: File, keepName: String): Int {
        var removed = 0
        serverDir.listFiles { f ->
            f.isFile && f.name.startsWith("${BdsSupport.ENTRY_NAME}.old-")
        }?.forEach { file ->
            if (file.name != keepName && runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            }
        }
        return removed
    }
}
