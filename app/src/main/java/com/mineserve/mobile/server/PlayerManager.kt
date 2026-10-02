package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

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

    /** 宽松解析：MC 的 JSON 可能含额外字段（bypassesPlayerLimit 等），全部忽略 */
    private val json = Json { ignoreUnknownKeys = true }

    /** MC 服务端工作目录（home/servers/{dirName}） */
    fun serverDir(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName")

    /** 读取 ops.json；文件不存在或解析失败时返回空列表 */
    fun readOps(dirName: String): List<OpEntry> {
        val dir = serverDir(dirName)
        val textFile = File(dir, "ops.txt")
        if (PowerNukkitXLayout.isPowerNukkitX(dir) && textFile.isFile) {
            return textFile.readLines().mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { OpEntry(it, "", 4) }
        }
        return readJsonList(File(dir, "ops.json"))
    }

    /** 读取 whitelist.json；文件不存在或解析失败时返回空列表 */
    fun readWhitelist(dirName: String): List<WhitelistEntry> {
        val dir = serverDir(dirName)
        val textFile = File(dir, "white-list.txt")
        if (PowerNukkitXLayout.isPowerNukkitX(dir) && textFile.isFile) {
            return textFile.readLines().mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { WhitelistEntry(it, "") }
        }
        return readJsonList(File(dir, "whitelist.json"))
    }

    /** 读取 banned-players.json；文件不存在或解析失败时返回空列表 */
    fun readBanned(dirName: String): List<BannedEntry> =
        readJsonList(File(serverDir(dirName), "banned-players.json"))

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

    // ── OP 管理 ──────────────────────────────────────────────────

    /**
     * 添加 OP（默认等级 4）
     * @return true 表示已发送
     */
    fun opPlayer(name: String): Boolean {
        return sendCmd("op $name")
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
     * @param name 玩家名
     * @param level OP 等级 (1-4)
     * @param dirName 服务端核心目录名
     * @return true 表示已发送命令并尝试修改 ops.json
     */
    fun opPlayerWithLevel(name: String, level: Int, dirName: String): Boolean {
        if (!termux.isMcRunning()) return false
        // 1. 发送 op 命令添加 OP（等级为 server.properties 默认值，通常 4）
        termux.sendCommand("op $name")
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

    /** 取消 OP */
    fun deopPlayer(name: String): Boolean = sendCmd("deop $name")

    // ── 白名单 ──────────────────────────────────────────────────

    /** 添加白名单 */
    fun whitelistAdd(name: String): Boolean = sendCmd("whitelist add $name")

    /** 移除白名单 */
    fun whitelistRemove(name: String): Boolean = sendCmd("whitelist remove $name")

    /** 开启白名单 */
    fun whitelistOn(): Boolean = sendCmd("whitelist on")

    /** 关闭白名单 */
    fun whitelistOff(): Boolean = sendCmd("whitelist off")

    // ── 踢出/封禁 ──────────────────────────────────────────────

    /**
     * 踢出玩家
     * @param reason 踢出原因，留空则使用默认
     */
    fun kickPlayer(name: String, reason: String = ""): Boolean {
        val cmd = if (reason.isBlank()) "kick $name" else "kick $name $reason"
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
     * 从日志中解析在线玩家列表
     * 匹配 "players online: <names>" 格式
     */
    fun parseOnlinePlayers(logLine: String): List<String>? {
        // 标准格式：There are 3 of a max of 20 players online: Alice, Bob, Charlie
        val regex = Regex("players online:\\s*(.*)")
        val match = regex.find(logLine) ?: return null
        val names = match.groupValues[1].trim()
        if (names.isEmpty()) return emptyList()
        // 玩家名可能包含下划线、数字，按逗号分隔
        return names.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

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
        return sendCmd("gamemode $modeName $name")
    }

    /**
     * 给玩家经验
     * @param amount 经验数量
     */
    fun giveXp(name: String, amount: Int): Boolean {
        if (amount <= 0) return false
        return sendCmd("xp give $name $amount")
    }
}
