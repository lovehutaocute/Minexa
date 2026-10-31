package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 玩家管理：通过 MC 的 JSON 文件读取玩家数据，通过控制台命令管理玩家。
 *
 * JSON 文件位于服务端工作目录（home/servers/{dirName}）：
 *  - ops.json：OP 列表
 *  - whitelist.json：白名单
 *  - banned-players.json：封禁列表
 *
 * 命令通过 [termux.sendCommand] 发送到 MC stdin，MC 执行后会回写对应 JSON 文件，
 * 可再次调用 read* 方法刷新本地视图。
 *
 * 重要：所有命令发送方法都会先调用 [termux.isMcRunning] 校验服务器状态，
 * 未运行时返回 false，由上层（ViewModel）给出错误反馈，避免静默失败。
 */
class PlayerManager(private val termux: TermuxRuntime) {

    /** 进服/离服日志解析：日志前缀(]: 结尾) + 玩家名(1-16位字母数字下划线) + 可选[IP]后缀 + (has) joined/left the game。
     * 以 ": " 锚定日志前缀结尾，过滤聊天消息（如 "<Steve> I joined the game"）误提取。 */
    private val joinLeaveStrictRegex = Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game")

    /** 宽松回退：兼容无日志前缀冒号的极端格式（如直接输出 "Steve joined the game"） */
    private val joinLeaveLooseRegex = Regex("([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game")
    private val powerNukkitJoinRegex = Regex("(?:^|[: ]\\s*)([A-Za-z0-9_]{1,16})(?:\\[[^]]*])?\\s+logged in with entity id", RegexOption.IGNORE_CASE)
    private val powerNukkitLeaveRegex = Regex("(?:^|[: ]\\s*)([A-Za-z0-9_]{1,16})(?:\\[[^]]*])?\\s+disconnected(?:[: ]|$)", RegexOption.IGNORE_CASE)

    fun extractPowerNukkitPlayerEvent(line: String): Pair<String, Boolean>? {
        powerNukkitJoinRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it to true }
        powerNukkitLeaveRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it to false }
        return null
    }

    // ── 官方 BDS（bedrock_server）的玩家事件 ────────────────────────
    //
    // BDS 不打印 Java 的 "joined the game"，它的格式是：
    //   [2024-01-01 00:00:00 INFO] Player connected: Steve, xuid: 2535412...
    //   [2024-01-01 00:00:00 INFO] Player Spawned: Steve xuid: 2535412..., pfid: ...
    //   [2024-01-01 00:00:00 INFO] Player disconnected: Steve, xuid: 2535412...
    //
    // 只能认 connected / disconnected：中间那条 "Player Spawned" 同一次连接也会出现，
    // 把它也算进服会让在线数翻倍。
    private val bdsJoinRegex = Regex("Player connected:\\s*([^,\\r\\n]+?)\\s*(?:,\\s*xuid:\\s*(\\d+))?\\s*$", RegexOption.IGNORE_CASE)
    private val bdsLeaveRegex = Regex("Player disconnected:\\s*([^,\\r\\n]+?)\\s*(?:,\\s*xuid:\\s*(\\d+))?\\s*$", RegexOption.IGNORE_CASE)

    /** BDS 的进服/离服事件：名字 + 是否进服 + 可选的 xuid（用于 xuid → 名字映射） */
    data class BdsPlayerEvent(val name: String, val joined: Boolean, val xuid: String?)

    fun extractBedrockPlayerEvent(line: String): BdsPlayerEvent? {
        if (!line.contains("Player connected", true) && !line.contains("Player disconnected", true)) return null
        bdsJoinRegex.find(line)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) return BdsPlayerEvent(name, true, m.groupValues.getOrNull(2)?.ifBlank { null })
        }
        bdsLeaveRegex.find(line)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) return BdsPlayerEvent(name, false, m.groupValues.getOrNull(2)?.ifBlank { null })
        }
        return null
    }

    /**
     * xuid → 玩家名映射。
     *
     * BDS 的 permissions.json **只存 xuid 不存名字**，只靠它渲染 OP 列表会全是数字。
     * 这里用玩家进服日志里带出来的 xuid 建映射，并落盘到服务器目录
     * （`.minexa-xuid-names.json`），换设备/重启后仍能显示真实名字。
     */
    private val bdsXuidNames = ConcurrentHashMap<String, String>()

    fun noteBedrockPlayer(name: String, xuid: String?) {
        if (xuid.isNullOrBlank() || name.isBlank()) return
        bdsXuidNames[xuid] = name
    }

    private fun xuidNamesFile(dirName: String) = File(serverDir(dirName), ".minexa-xuid-names.json")

    /** 从服务器目录载入 xuid 映射（IO 线程调用） */
    fun loadBedrockXuidNames(dirName: String) {
        try {
            val file = xuidNamesFile(dirName)
            if (!file.isFile) return
            val map = json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                file.readText()
            )
            map.forEach { (k, v) -> bdsXuidNames.putIfAbsent(k, v) }
        } catch (_: Throwable) {
            // 映射只是显示优化，读失败不影响功能
        }
    }

    /** 把 xuid 映射写回服务器目录（IO 线程调用） */
    fun saveBedrockXuidNames(dirName: String) {
        try {
            val file = xuidNamesFile(dirName)
            file.writeText(
                json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    bdsXuidNames.toMap()
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun bdsDisplayName(xuid: String): String = bdsXuidNames[xuid] ?: xuid

    @Serializable
    data class OpEntry(val name: String, val uuid: String, val level: Int = 4)

    @Serializable
    data class WhitelistEntry(val name: String, val uuid: String)

    @Serializable
    data class BannedEntry(
        val name: String,
        val uuid: String,
        val reason: String = "",
        val expires: String = "forever",
        val source: String = ""
    )

    /** BDS `permissions.json` 的条目：只有权限和 xuid，没有名字 */
    @Serializable
    data class BdsPermissionEntry(val permission: String = "member", val xuid: String = "")

    /** BDS `allowlist.json` 的条目 */
    @Serializable
    data class BdsAllowlistEntry(
        val name: String = "",
        val xuid: String = "",
        val ignoresPlayerLimit: Boolean = false
    )

    /** 宽松解析：MC 的 JSON 可能含额外字段（bypassesPlayerLimit 等），全部忽略 */
    private val json = Json { ignoreUnknownKeys = true }

    /** MC 服务端工作目录（统一存储目录或旧私有目录，由 ServerStorage 解析） */
    fun serverDir(dirName: String): File = termux.serverDirFor(dirName)

    /**
     * 读取 OP 列表。
     *
     * @param bds 官方 BDS 核心：OP 存在 `permissions.json`（permission=operator），
     *            条目只带 xuid，名字靠 [bdsXuidNames] 反查；查不到时直接显示 xuid。
     */
    fun readOps(dirName: String, bds: Boolean = false): List<OpEntry> {
        val dir = serverDir(dirName)
        if (bds) {
            return readJsonList<BdsPermissionEntry>(File(dir, "permissions.json"))
                .filter { it.permission.equals("operator", ignoreCase = true) && it.xuid.isNotBlank() }
                .map { OpEntry(name = bdsDisplayName(it.xuid), uuid = it.xuid, level = 4) }
        }
        val textFile = File(dir, "ops.txt")
        if (PowerNukkitXLayout.isPowerNukkitX(dir) && textFile.isFile) {
            return textFile.readLines().mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { OpEntry(it, "", 4) }
        }
        return readJsonList(File(dir, "ops.json"))
    }

    /**
     * 读取白名单列表。
     *
     * @param bds 官方 BDS 核心：白名单存在 `allowlist.json`（含 name 与 xuid）
     */
    fun readWhitelist(dirName: String, bds: Boolean = false): List<WhitelistEntry> {
        val dir = serverDir(dirName)
        if (bds) {
            return readJsonList<BdsAllowlistEntry>(File(dir, "allowlist.json"))
                .filter { it.name.isNotBlank() || it.xuid.isNotBlank() }
                .map { entry ->
                    // allowlist.json 同时带 name 与 xuid，顺手补全 xuid → 名字映射，
                    // 这样 permissions.json 里的 OP 条目也能显示成玩家名
                    if (entry.xuid.isNotBlank() && entry.name.isNotBlank()) bdsXuidNames[entry.xuid] = entry.name
                    WhitelistEntry(
                        name = entry.name.ifBlank { bdsXuidNames[entry.xuid] ?: entry.xuid },
                        uuid = entry.xuid
                    )
                }
        }
        val textFile = File(dir, "white-list.txt")
        if (PowerNukkitXLayout.isPowerNukkitX(dir) && textFile.isFile) {
            return textFile.readLines().mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { WhitelistEntry(it, "") }
        }
        return readJsonList(File(dir, "whitelist.json"))
    }

    /** 读取封禁列表；BDS 官方服务端没有封禁机制，恒为空 */
    fun readBanned(dirName: String, bds: Boolean = false): List<BannedEntry> =
        if (bds) emptyList() else readJsonList(File(serverDir(dirName), "banned-players.json"))

    /** 通用 JSON 列表读取：文件不存在/为空/解析失败均返回空列表，绝不抛异常 */
    private inline fun <reified T> readJsonList(file: File): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            if (content.isBlank()) emptyList() else json.decodeFromString(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── 命令发送（带服务器状态校验） ──────────────────────────────

    /**
     * 发送命令到 MC 控制台
     * @return true 表示已发送，false 表示服务器未运行
     */
    private fun sendCmd(line: String): Boolean {
        if (!termux.isMcRunning()) return false
        termux.sendCommand(line)
        return true
    }

    /**
     * 基岩版允许玩家名带空格（如 `Steve Smith`），命令里必须整体加引号，
     * 否则 BDS 会把后半截当成额外参数。
     */
    private fun quoted(name: String): String =
        if (name.any { it.isWhitespace() }) "\"$name\"" else name

    // ── OP 管理 ──────────────────────────────────────────────────

    /**
     * 添加 OP（默认等级 4）
     * @return true 表示已发送
     */
    fun opPlayer(name: String, bds: Boolean = false): Boolean {
        return sendCmd("op ${quoted(name)}")
    }

    /**
     * 添加 OP 并指定等级。
     *
     * 实现策略：原版 MC 的 `/op` 命令不支持 level 参数（新 OP 等级由 server.properties
     * 的 op-permission-level 决定，默认 4）。因此先发送 `op $name` 添加 OP，
     * 再直接修改 ops.json 中该玩家的 level 字段。
     *
     * 注意：ops.json 的 level 修改需重启服务器才能生效到权限系统（运行中的 OP
     * 权限已由 op 命令按默认等级授予）。
     *
     * BDS 只有 operator / member 两档权限，没有等级概念，因此 [bds] 为 true 时
     * 只发送 `op` 命令，不碰 ops.json（那个文件 BDS 根本不读，写进去只会留下垃圾）。
     *
     * @param name 玩家名
     * @param level OP 等级 (1-4)
     * @param dirName 服务端核心目录名
     * @return true 表示已发送命令并尝试修改 ops.json
     */
    fun opPlayerWithLevel(name: String, level: Int, dirName: String, bds: Boolean = false): Boolean {
        if (!termux.isMcRunning()) return false
        // 1. 发送 op 命令添加 OP（等级为 server.properties 默认值，通常 4）
        termux.sendCommand("op ${quoted(name)}")
        if (bds) return true
        if (PowerNukkitXLayout.isPowerNukkitX(serverDir(dirName))) return true
        // 2. 等待 MC 回写 ops.json（含 UUID）
        try { Thread.sleep(800) } catch (_: InterruptedException) {}
        // 3. 修改 ops.json 中该玩家的 level 字段
        try {
            val opsFile = File(serverDir(dirName), "ops.json")
            val ops = readOps(dirName).toMutableList()
            val idx = ops.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx >= 0) {
                ops[idx] = ops[idx].copy(level = level)
            } else {
                // MC 尚未回写（极端时序），添加临时条目（UUID 留空，重启后 MC 会补全）
                ops.add(OpEntry(name = name, uuid = "", level = level))
            }
            opsFile.writeText(json.encodeToString(ListSerializer(OpEntry.serializer()), ops))
        } catch (_: Exception) {
            // ops.json 修改失败不影响 op 命令发送结果
        }
        return true
    }

    /**
     * 取消 OP。
     *
     * BDS 的 `deop` 只对在线玩家生效（离线玩家匹配不到 target），所以先直接改
     * `permissions.json` 删掉条目，再把命令发一遍给在线的那一位立即生效 ——
     * 两个动作都是幂等的，重复执行不会出问题。
     */
    fun deopPlayer(name: String, dirName: String? = null, bds: Boolean = false): Boolean {
        if (bds && dirName != null) removeBedrockOperator(dirName, name)
        return sendCmd("deop ${quoted(name)}")
    }

    /** 从 BDS 的 permissions.json 里移除某个玩家（按名字或 xuid 匹配） */
    private fun removeBedrockOperator(dirName: String, nameOrXuid: String) {
        try {
            val file = File(serverDir(dirName), "permissions.json")
            if (!file.isFile) return
            val entries = readJsonList<BdsPermissionEntry>(file)
            if (entries.isEmpty()) return
            val targetXuid = bdsXuidNames.entries.firstOrNull { it.value.equals(nameOrXuid, true) }?.key
            // 只删除"权限是 operator 且就是这个玩家"的条目；member 条目必须原样保留
            val kept = entries.filterNot { entry ->
                entry.permission.equals("operator", true) &&
                    (entry.xuid.equals(nameOrXuid, true) || (targetXuid != null && entry.xuid == targetXuid))
            }
            file.writeText(json.encodeToString(ListSerializer(BdsPermissionEntry.serializer()), kept))
        } catch (_: Throwable) {
            // 文件损坏时不动它，让 deop 命令自己去处理在线玩家
        }
    }

    // ── 白名单 ──────────────────────────────────────────────────

    /** 添加白名单；BDS 的命令是 `allowlist add` */
    fun whitelistAdd(name: String, bds: Boolean = false): Boolean =
        sendCmd(if (bds) "allowlist add ${quoted(name)}" else "whitelist add ${quoted(name)}")

    /** 移除白名单 */
    fun whitelistRemove(name: String, bds: Boolean = false): Boolean =
        sendCmd(if (bds) "allowlist remove ${quoted(name)}" else "whitelist remove ${quoted(name)}")

    /** 开启白名单 */
    fun whitelistOn(bds: Boolean = false): Boolean =
        sendCmd(if (bds) "allowlist on" else "whitelist on")

    /** 关闭白名单 */
    fun whitelistOff(bds: Boolean = false): Boolean =
        sendCmd(if (bds) "allowlist off" else "whitelist off")

    // ── 踢出/封禁 ──────────────────────────────────────────────

    /**
     * 踢出玩家
     * @param reason 踢出原因，留空则使用默认
     */
    fun kickPlayer(name: String, reason: String = ""): Boolean {
        val cmd = if (reason.isBlank()) "kick ${quoted(name)}" else "kick ${quoted(name)} $reason"
        return sendCmd(cmd)
    }

    /**
     * 永久封禁玩家
     * @param reason 封禁原因
     */
    fun banPlayer(name: String, reason: String = "Banned by admin"): Boolean {
        return sendCmd(if (reason.isBlank()) "ban $name" else "ban $name $reason")
    }

    /**
     * 限时封禁玩家
     * @param duration 时长（如 "30m"、"1h"、"7d"）
     * @param reason 封禁原因
     */
    fun tempBanPlayer(name: String, duration: String, reason: String = ""): Boolean {
        val cmd = if (reason.isBlank()) "tempban $name $duration"
                  else "tempban $name $duration $reason"
        return sendCmd(cmd)
    }

    /** 解除封禁 */
    fun pardonPlayer(name: String): Boolean = sendCmd("pardon $name")

    // ── 在线玩家列表 ────────────────────────────────────────────

    /** 请求在线玩家列表（发送 list 命令，结果通过日志解析） */
    fun requestOnlineList(): Boolean = sendCmd("list")

    /**
     * 从日志中解析在线玩家列表。
     *
     * Java 版：`There are 3 of a max of 20 players online: Alice, Bob, Charlie`
     * BDS：`There are 3/10 players online:` —— 名字在**后续的行**里，本行内容为空。
     *
     * @param onlineCount 本行解析出的在线人数；BDS 的行只有空名单，
     *   若此时真有人在线就必须返回 null（表示"本行没有名单"，保留事件累积的名单），
     *   否则会把刚进服的玩家从列表里抹掉。
     */
    fun parseOnlinePlayers(logLine: String, onlineCount: Int? = null): List<String>? {
        if (!logLine.contains("players online")) return null
        val regex = Regex("players online:\\s*(.*)")
        val match = regex.find(logLine) ?: return null
        val names = match.groupValues[1].trim()
        if (names.isEmpty()) {
            // 只有确定是 0 人才清空名单
            return if (onlineCount == 0) emptyList() else null
        }
        // 玩家名可能包含下划线、数字，按逗号分隔
        return names.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 解析 BDS `list` 命令的多行输出（名字在头行的**后面**）：
     * ```
     * [INFO] There are 2/10 players online:
     * [INFO] - Steve
     * [INFO] - Alex
     * ```
     * 也兼容不带 `- ` 前缀的版本。返回 null 表示这行不是名字（收集应当中止）。
     */
    fun parseBedrockListEntry(line: String): String? {
        val tail = line.substringAfterLast("] ", line).trim()
        val name = tail.removePrefix("- ").trim()
        if (name.isEmpty() || name.length > 32) return null
        if (!bdsPlayerNameRegex.matches(name)) return null
        // 启动日志 / 命令回显经常也是短句，靠这几个词挡掉
        if (name.contains("server", true) || name.contains("player", true)) return null
        return name
    }

    private val bdsPlayerNameRegex = Regex("^[\\p{L}\\p{N}_ .'\\-]{1,32}$")

    /**
     * 从进服/离服日志行中提取玩家名。
     * 支持多种格式：`Steve joined the game`、`Steve has joined the game`、
     * `Steve[/127.0.0.1:5555] joined the game`（带 IP 后缀）、带日志时间戳前缀等。
     * 玩家名按 MC 格式（1-16 位字母/数字/下划线）校验，过滤聊天消息等误匹配。
     * @return 玩家名；无法提取或格式非法时返回 null
     */
    fun extractPlayerName(logLine: String): String? {
        // 先严格匹配（冒号锚定日志前缀，防聊天误报），失败再宽松回退（兼容无前缀格式）
        val m = joinLeaveStrictRegex.find(logLine) ?: joinLeaveLooseRegex.find(logLine) ?: return null
        return m.groupValues[1]
    }

    // ── 游戏模式切换 ────────────────────────────────────────────

    /**
     * 设置玩家游戏模式
     * @param mode 0=生存 1=创造 2=冒险 3=旁观
     */
    fun setGameMode(name: String, mode: Int): Boolean {
        val modeName = when (mode.coerceIn(0, 3)) {
            0 -> "survival"; 1 -> "creative"; 2 -> "adventure"; 3 -> "spectator"; else -> "survival"
        }
        // 只发 1.13+ 的 gamemode <模式名> <玩家>：现代核心（Paper/Spigot/Fabric/Forge 等）
        // 均为此语法。原先同时发旧版 gamemode <玩家> <模式名>，在 1.13+ 上第二条会被解析成
        // 「未知游戏模式 <玩家名>」，控制台每设一次多一条语法错误，且返回值只反映第二条。
        //
        // 基岩版语法一致，但**名字必须整体加引号**：BDS 允许 `Steve Smith` 这种带空格的
        // 名字，不加引号会被拆成两个参数，命令静默失效。
        return sendCmd("gamemode $modeName ${quoted(name)}")
    }

    /**
     * 给玩家经验
     * @param amount 经验数量
     *
     * 两套核心语法不同：Java 是 `xp give <玩家> <数量>`，基岩是 `xp <数量> <玩家>`。
     * 早先两处都发 Java 语法，BDS 下会被当成未知命令丢掉。
     */
    fun giveXp(name: String, amount: Int, bds: Boolean = false): Boolean {
        if (amount <= 0) return false
        return if (bds) sendCmd("xp $amount ${quoted(name)}") else sendCmd("xp give $name $amount")
    }
}
