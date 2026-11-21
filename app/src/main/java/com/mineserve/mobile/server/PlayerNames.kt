package com.mineserve.mobile.server

/**
 * 玩家名清洗与校验（纯函数，无状态）。
 *
 * 背景：服务端会把颜色码写进控制台输出（Paper + 上色插件、BDS/Geyser 的 `§` 前缀），
 * 而 Android 侧读取日志时 ESC 字符（U+001B）会被吞掉，只剩字面 `[0m` / `[37m`。
 * 不清理就会被当成名字的一部分，界面出现 `[0mdeepseek_flash`、`wehxc147[37m`
 * 这类"永远在线、重启也在"的幽灵条目，且复制/踢人/改模式发出的名字也是脏的。
 *
 * 两级清洗：
 *  - [stripMarkup]：**行级**，跑正则之前调用。这是关键 —— 名字正则 `[A-Za-z0-9_]{1,16}`
 *    遇到 `[0mSteve joined the game` 时，`[` 会挡住严格匹配，宽松回退正好从 `0m`
 *    起匹配，产出的名字 `0mSteve` 已无 `[`，事后再 sanitize 也救不回来。
 *  - [sanitize]：名字级，清洗 + 合法性校验。
 */
object PlayerNames {

    /** 正常 ANSI CSI 序列：ESC [ ... 终止字节 */
    private val ansiEscapeRegex = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /**
     * ESC 被吞后残留的伪序列，如 `[0m`、`[1;32m`、`[38;5;202m`。
     * 上限放到 8 个字符是为了覆盖多属性/256 色写法；再长就交给 [illegalChars] 兜底。
     */
    private val ansiResidueRegex = Regex("\\[[0-9;]{0,8}m")

    /** 基岩版/插件的 `§` 颜色码，逐对删除（`§x§f§f§0§0§0§0` 这类十六进制写法也能清干净） */
    private val sectionColorRegex = Regex("\\u00A7[0-9a-fk-orxX]")
    private val controlCharRegex = Regex("[\\u0000-\\u001F\\u007F]")

    /** 名字里绝不可能出现的字符：出现即说明是解析垃圾 */
    private val illegalChars = charArrayOf('[', ']', ',', '\\', '"', '\u00A7')

    /**
     * 行级清洗：剔除所有颜色/控制码，保留行内其余文本。
     *
     * `[12:00:00 INFO]: [0mSteve joined the game` → `[12:00:00 INFO]: Steve joined the game`。
     * `[数字;…m` 的判据足够窄，不会误伤时间戳或 `[INFO]` 这类日志前缀。
     */
    fun stripMarkup(raw: String): String {
        var s = ansiEscapeRegex.replace(raw, "")
        s = ansiResidueRegex.replace(s, "")
        s = sectionColorRegex.replace(s, "")
        return controlCharRegex.replace(s, "")
    }

    /**
     * 清洗并校验玩家名。
     *
     * 允许：字母（含中文）/数字/下划线/空格/点/星号/连字符/撇号 —— 覆盖 Java 版限制名、
     * BDS 的带空格名、以及 Geyser/Floodgate 的 `.Name`、`* Name` 前缀。
     *
     * @return 清洗后的名字；清洗后为空、超长或含非法字符时返回 null（调用方丢弃该条）
     */
    fun sanitize(raw: String): String? {
        val s = stripMarkup(raw).trim()
        if (s.isEmpty() || s.length > 32) return null
        if (s.any { c -> illegalChars.any { it == c } }) return null
        if (s.none { it.isLetterOrDigit() }) return null
        return s
    }

    // ── Java 版进服/离服 ────────────────────────────────────────────
    //
    // 正则说明：日志前缀(]: 结尾) + 玩家名(1-16位字母数字下划线) + 可选[IP]后缀 +
    // (has) joined/left the game。以 ": " 锚定前缀结尾，过滤聊天消息
    // （如 "<Steve> I joined the game"）误提取。

    private val joinLeaveStrictRegex =
        Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game")
    private val joinLeaveLooseRegex =
        Regex("([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game")

    /** 聊天前缀 `<Steve>`：用于把聊天内容从进出服日志里排除 */
    private val chatPrefixRegex = Regex("<[^<>]{1,32}>")

    /**
     * 从进服/离服日志行提取玩家名（行级清洗 → 严格匹配 → 宽松回退 → 名字校验）。
     *
     * @return 玩家名；无法提取或格式非法时返回 null
     */
    fun fromJoinLeaveLine(logLine: String): String? {
        val line = stripMarkup(logLine)
        // 聊天消息（`INFO]: <Steve> I joined the game`）不含日志前缀冒号，严格匹配不命中，
        // 但宽松回退会抓到 `I` —— 于是凭空多出一个叫 `I` 的在线玩家。聊天前缀出现在
        // 事件短语之前，就说明这是聊天内容而不是玩家事件。
        val chat = chatPrefixRegex.find(line)
        val eventAt = listOf(line.indexOf("joined the game"), line.indexOf("left the game"))
            .filter { it >= 0 }
            .minOrNull()
        if (chat != null && eventAt != null && chat.range.first < eventAt) return null

        val m = joinLeaveStrictRegex.find(line) ?: joinLeaveLooseRegex.find(line) ?: return null
        return sanitize(m.groupValues[1])
    }
}
