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
 *
 * 宽松回退的名字段带左边界断言 `(?<![A-Za-z0-9_.])`，代理前缀只在词首才被识别：
 * `com.example joined the game` 是主机名/插件名而非玩家事件，不加断言会产出 `.example`。
 * 断言挡住之后回退还会继续往右找词首，于是 `com.example has joined the game` 会落到
 * `has` 上 —— 由 [reservedTokens] 在**回退分支**上兜住（严格分支不拦，`has` 是真名）。
 */
object PlayerNames {

    /** 正常 ANSI CSI 序列：ESC [ ... 终止字节 */
    private val ansiEscapeRegex = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /**
     * ESC 被吞后残留的伪序列，如 `[0m`、`[1;32m`、`[38;5;202m`、`[38;2;255;0;0m`。
     *
     * 不限长度：真彩色/组合写法的参数个数没有上限，任何长度上限都会漏掉更长的那种，
     * 残留的 `[` 会让整条名字被判非法、玩家从列表里消失。判据本身足够窄 ——
     * `[` + 数字/分号 + `m`，`[INFO]` 与时间戳都不匹配。
     */
    private val ansiResidueRegex = Regex("\\[[0-9;]*m")

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
     *
     * 控制字符里只有 tab 这类空白会**换成空格**：直接删掉会让 `Steve\tjoined` 粘成
     * `Stevejoined`，随后名字正则的 `\s+` 匹配失败、整条事件漏记。
     */
    fun stripMarkup(raw: String): String {
        var s = ansiEscapeRegex.replace(raw, "")
        s = ansiResidueRegex.replace(s, "")
        s = sectionColorRegex.replace(s, "")
        return controlCharRegex.replace(s) { m ->
            if (m.value[0].isWhitespace()) " " else ""
        }
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
    //
    // 名字允许带代理前缀：Geyser 默认给基岩玩家加 `.`（`.Steve`），Floodgate 常用
    // `*`（`* Steve`）。前缀必须保留 —— `list` 响应会原样带前缀，这里若只取裸名，
    // 同一个玩家会在名单里变成两条（`.Steve` 与 `Steve`），离服只删掉裸名，
    // `.Steve` 就永久留在在线列表；发给 `kick`/`gamemode` 的名字也必须是带前缀的全名。
    //
    // 宽松回退带左边界断言 `(?<![A-Za-z0-9_.])`：前缀只在「词首」才成立。否则
    // `com.example joined the game` 会被当成前缀名 `.example`（把 `.` 误读成 Geyser
    // 前缀），`foo.bar` 这类主机名/插件名同样会漏出垃圾条目。真的叫 `.Steve` 的写法
    // 前面必然是行首、空格、`]` 或 `:`，不受影响。严格分支以 `: ` 锚定，不需要断言。

    private val joinLeaveStrictRegex = Regex(
        ":\\s*((?:\\*\\s*|\\.)?[A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game"
    )
    private val joinLeaveLooseRegex = Regex(
        "(?<![A-Za-z0-9_.])((?:\\*\\s*|\\.)?[A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game"
    )

    /** 聊天前缀 `<Steve>`：用于把聊天内容从进出服日志里排除 */
    private val chatPrefixRegex = Regex("<[^<>]{1,32}>")

    /**
     * 语法词黑名单：宽松回退可能把事件短语里的词当成名字。
     *
     * **只作用于宽松回退分支**（严格分支由 `:` 锚定，捕获组在这个位置只可能是真名）。
     * 唯一需要挡的是 `has`：`(?:has\s+)?` 可选，`com.example has joined the game` 里
     * 断言挡掉 `example` 后，回退从 `has` 起照样能命中「…joined the game」。
     *
     * 不扩词表：`joined`/`left`/`the`/`game` 在 `\s+(?:has\s+)?(?:joined|left)` 的结构下
     * 不可能成为捕获组，而 `Game`、`has` 本身都是合法玩家名 —— 这是一张「语法词」表，
     * 不是「非法名」表，别往里加名字。
     */
    private val reservedTokens = setOf("has")

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

        val strict = joinLeaveStrictRegex.find(line)
        val m = strict ?: joinLeaveLooseRegex.find(line) ?: return null
        val name = sanitize(m.groupValues[1])
        // 守卫只在回退分支上生效：`INFO]: has joined the game` 里 `has` 是被 `: ` 锚定的
        // 真玩家，照原样返回；只有回退才可能把事件短语的 `has` 当成名字。
        return if (strict == null) name?.takeUnless { it.lowercase() in reservedTokens } else name
    }

    // ── 名单合并 ────────────────────────────────────────────────────
    //
    // 事件流与 `list` 响应是两条独立来源：`list` 保留代理前缀（`.Steve`），
    // 进服日志在识别不出前缀时只会给出裸名（`Steve`）。不归一化就会同一人两条，
    // 且离服删不掉带前缀那条。

    /**
     * 名字归一化键：去掉 Geyser/Floodgate 前缀（`*`、`.`）与首尾空白，忽略大小写。
     *
     * **只用于比较**，绝不用于发送命令 —— 命令必须用带前缀的全名。
     */
    fun matchKey(name: String): String =
        name.trim().removePrefix("*").trim().removePrefix(".").trim().lowercase()

    /** 是否带 Geyser/Floodgate 代理前缀 —— 即该玩家来自基岩版 */
    fun hasProxyPrefix(name: String): Boolean {
        val s = name.trimStart()
        return s.startsWith(".") || s.startsWith("*")
    }

    /**
     * 把新出现的玩家并入名单：等价名字（同前缀状态 + 忽略大小写）已存在时不算新增。
     *
     * **前缀状态必须一起比**：Geyser 给基岩玩家加 `.`，正是为了让基岩 `Steve` 和 Java
     * `Steve` 能同时在线。只按等价键合并会把这两个不同玩家并成一个 —— 界面上横幅人数
     * （事件计数）与「在线 N」（名单 size）随即分叉，能看到「2 人在线」但列表只有 1 行。
     * 因此迁移方向选「宁可多一行也不吞人」：多出来的重复行会被下一次离服事件精确删掉，
     * 而吞掉的人只能等用户手动刷新才回来。
     *
     * @return 未发生变化时返回**同一个列表实例**，调用方可据此跳过状态更新
     */
    fun mergeInto(current: List<String>, name: String): List<String> {
        if (name in current) return current
        val key = matchKey(name)
        val prefixed = hasProxyPrefix(name)
        if (current.any { hasProxyPrefix(it) == prefixed && matchKey(it) == key }) return current
        return current + name
    }

    /**
     * 按等价名字从名单移除（离服事件可能给的是裸名，而名单里存的是带前缀的全名）。
     *
     * 精确命中优先：Geyser 同名配置下 `Steve`（Java）与 `.Steve`（基岩）会同时在线，
     * 二者的等价键相同，按等价键全删会连坐两个玩家。
     *
     * @return 未发生变化时返回**同一个列表实例** —— 调用方必须据此判断「真的删掉了」，
     *         否则会出现列表 2 行、计数只有 1 的错位
     */
    fun removeFrom(current: List<String>, name: String): List<String> {
        // 精确命中只删一条（`-` 移除首个匹配），与等价路径的删除粒度保持一致
        if (name in current) return current - name
        val key = matchKey(name)
        val hits = current.filter { matchKey(it) == key }
        // 等价命中不唯一时说明名单里本就有同名跨平台的两人，无法判断删谁 —— 不动
        if (hits.size != 1) return current
        return current - hits[0]
    }
}
