package com.mineserve.mobile.server

/**
 * `server.properties` 的转义编解码（java.util.Properties 语义）。
 *
 * 为什么必须有：
 * Java 服务端**每次启动都会重写** `server.properties`，并把值里的 `:` 转义成 `\:`：
 * ```
 * level-type=minecraft\:flat
 * generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1}],"biome"\:"minecraft\:plains"}
 * ```
 * 不解码的话，`level-type` 永远和 UI 选项对不上，`generator-settings` 也**不是合法 JSON**
 * （实测见 `docs/JAVA-ADVANCED-WORLD-MANAGEMENT-PLAN.md` §2.8）。
 *
 * 解码规则（与 Properties.load 一致）：`\t` `\n` `\r` `\f` `\\` `\uXXXX`，
 * 以及"反斜杠 + 任意其它字符 = 该字符本身"。
 *
 * 编码只在**必要时**进行：值里的换行/制表/首尾空格必须转义，其余（含 `:` 与 `=`）
 * 在值中无需转义——服务端自己会加 `\:`，我们不加，避免两边来回改写。
 */
object PropertiesEscape {

    /** 解码一个值（幂等：对没有反斜杠的值原样返回）。 */
    fun decode(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                't' -> { sb.append('\t'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        sb.append(code.toChar())
                        i += 6
                    } else {
                        // \u 后面不是 4 位十六进制：按"反斜杠丢弃"处理，与 Properties 一致
                        sb.append('u')
                        i += 2
                    }
                }
                else -> { sb.append(next); i += 2 }
            }
        }
        return sb.toString()
    }

    /**
     * 编码一个值：只转义会破坏文件结构的字符。
     *
     * 不做 `:` → `\:`（服务端会自己加，我们加了反而会在下一次读取时被解码两次）。
     */
    fun encode(value: String): String {
        if (value.none { it == '\n' || it == '\r' || it == '\t' || it == '\\' }) {
            return if (value.startsWith(" ")) "\\" + value else value
        }
        val sb = StringBuilder(value.length + 8)
        if (value.startsWith(" ")) sb.append('\\')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
