package com.mineserve.mobile.server

/**
 * 玩家名清洗与校验（纯函数，无状态）。
 *
 * 背景：服务端会把 ANSI 颜色码写进控制台输出（Paper + 上色插件、BDS 的彩色提示），
 * 而 Android 侧读取日志时 ESC 字符（U+001B）会被吞掉，只剩字面 `[0m` / `[37m`。
 * 不清理就会被当成名字的一部分，界面出现 `[0mdeepseek_flash`、`wehxc147[37m`
 * 这类"永远在线、重启也在"的幽灵条目，且复制/踢人/改模式发出的名字也是脏的。
 */
object PlayerNames {

    /** 正常 ANSI CSI 序列：ESC [ ... 终止字节 */
    private val ansiEscapeRegex = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** ESC 被吞后残留的伪序列，如 `[0m`、`[1;32m`（MC 名字里不可能出现 `[`） */
    private val ansiResidueRegex = Regex("\\[[0-9;]{0,4}m")
    private val controlCharRegex = Regex("[\\u0000-\\u001F\\u007F]")

    /** 名字里绝不可能出现的字符：出现即说明是解析垃圾 */
    private val illegalChars = charArrayOf('[', ']', ',', '\\', '"')

    /**
     * 清洗并校验玩家名。
     *
     * 允许：字母（含中文）/数字/下划线/空格/点/星号/连字符/撇号 —— 覆盖 Java 版限制名、
     * BDS 的带空格名、以及 Geyser/Floodgate 的 `.Name`、`* Name` 前缀。
     *
     * @return 清洗后的名字；清洗后为空、超长或含非法字符时返回 null（调用方丢弃该条）
     */
    fun sanitize(raw: String): String? {
        var s = ansiEscapeRegex.replace(raw, "")
        s = ansiResidueRegex.replace(s, "")
        s = controlCharRegex.replace(s, "").trim()
        if (s.isEmpty() || s.length > 32) return null
        if (s.any { c -> illegalChars.any { it == c } }) return null
        if (s.none { it.isLetterOrDigit() }) return null
        return s
    }
}
