package com.mineserve.mobile.server

/**
 * server.properties 的保真模型。
 *
 * 设计目标：**编辑属性时不破坏用户文件**。原实现把文件读成 `Map` 后整份重写，
 * 结果是注释、空行分组和行序全部丢失——用户在 UI 之外手写的注释会被抹掉。
 * 这里改为把文件视为「有序的行序列」，只替换被编辑的 key 所在行，
 * 其余行（注释、空行、未识别的行）原样回写。
 *
 * 格式约定（与服务端原生 java.util.Properties 保持一致的有意取舍）：
 * - `#` 开头的行为注释，原样保留。
 * - `key=value` 按**第一个 `=`** 分割，值可能为空、可能含 `=`。
 * - 转义序列（`\:` `\=` `\ ` `\t` `\\`）**不解码也不编码**，原样透传，
 *   避免「读进来又写回去、把用户写的转义改写成别的形式」。
 * - 无法解析的非空行按注释处理，确保不丢内容。
 *
 * 本类不依赖 Android API，可直接单元测试。
 */
sealed interface PropertiesEntry {
    /** 注释行或空行，[raw] 为不含换行符的原始文本。 */
    data class Comment(val raw: String) : PropertiesEntry

    /** `key=value` 条目，[value] 为 `=` 之后的原始文本（未 trim）。 */
    data class KeyValue(val key: String, val value: String) : PropertiesEntry
}

/** 布尔值的序列化格式：Java/PNX 用 `true`/`false`，部分第三方实现用 `on`/`off`。 */
enum class BoolFormat { TrueFalse, OnOff }

/**
 * 有序的 server.properties 文档。
 *
 * 不可变语义由调用方保证：[get]/[set]/[remove] 就地修改本实例。
 *
 * 行尾风格是**有意归一化**的：CRLF 输入统一输出为 LF（见 `ServerPropertiesTest`
 * 的「CRLF 文件还原为标准换行且不残留回车」用例）。除此之外读写往返逐字节一致。
 */
class ServerProperties private constructor(
    private val entries: MutableList<PropertiesEntry>
) {

    /** 有序条目（只读视图），供需要逐行处理的场景使用。 */
    val allEntries: List<PropertiesEntry> get() = entries.toList()

    /**
     * 按 key 取值；不存在返回 null。
     *
     * 键重复时**后出现的生效**，与 java.util.Properties 及原实现一致。
     */
    operator fun get(key: String): String? {
        var found: String? = null
        for (entry in entries) {
            if (entry is PropertiesEntry.KeyValue && entry.key == key) found = entry.value
        }
        return found?.trim()
    }

    /**
     * 设置 key 的值：已存在则**就地更新该行**（保持行序与前后注释不动），
     * 不存在则追加到文件末尾。
     */
    operator fun set(key: String, value: String) {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry is PropertiesEntry.KeyValue && entry.key == key) {
                entries[i] = PropertiesEntry.KeyValue(key, value)
                return
            }
        }
        appendEntry(PropertiesEntry.KeyValue(key, value))
    }

    /**
     * 追加新条目。
     *
     * 解析时结尾换行会留下一个 `Comment("")` 占位，直接 add 会在它之后插入，
     * 结果是「多一个空行 + 丢失结尾换行」。这里插到它之前，保持文件形态。
     */
    private fun appendEntry(entry: PropertiesEntry) {
        val last = entries.lastOrNull()
        if (last is PropertiesEntry.Comment && last.raw.isEmpty()) {
            entries.add(entries.size - 1, entry)
        } else {
            entries.add(entry)
        }
    }

    fun containsKey(key: String): Boolean = get(key) != null

    /** 移除 key 所在的行（若存在）。 */
    fun remove(key: String) {
        entries.removeAll { it is PropertiesEntry.KeyValue && it.key == key }
    }

    // ── 类型化读取 ──────────────────────────────────────────────

    /** 读取布尔值，兼容 `true/false` 与 `on/off`；不存在或格式不符返回 null。 */
    fun getBool(key: String): Boolean? = when (get(key)?.lowercase()) {
        "true", "on", "yes" -> true
        "false", "off", "no" -> false
        else -> null
    }

    fun getInt(key: String): Int? = get(key)?.toIntOrNull()

    // ── 类型化写入 ──────────────────────────────────────────────

    fun setBool(key: String, value: Boolean, format: BoolFormat = BoolFormat.TrueFalse) {
        set(key, when (format) {
            BoolFormat.TrueFalse -> value.toString()
            BoolFormat.OnOff -> if (value) "on" else "off"
        })
    }

    fun setInt(key: String, value: Int) = set(key, value.toString())

    /** 转成普通 Map（有序），保留原实现给调用方的语义。 */
    fun toMap(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (entry in entries) {
            if (entry is PropertiesEntry.KeyValue && entry.key.isNotEmpty()) {
                result[entry.key] = entry.value.trim()
            }
        }
        return result
    }

    /**
     * 序列化回文本。
     *
     * 未做任何修改时输出与输入**逐字节一致**（含结尾换行符的有无）。
     * 唯一的例外是行尾风格：CRLF 输入会被归一化为 LF，这是有意为之。
     */
    fun toText(): String {
        val sb = StringBuilder()
        for ((index, entry) in entries.withIndex()) {
            if (index > 0) sb.append('\n')
            when (entry) {
                is PropertiesEntry.Comment -> sb.append(entry.raw)
                is PropertiesEntry.KeyValue -> sb.append(entry.key).append('=').append(entry.value)
            }
        }
        return sb.toString()
    }

    /**
     * 把 [props] 合并进本实例：逐条 set（已存在的行就地更新、缺失的追加到末尾），
     * 文件中其它未被提及的键、注释与空行全部保留。
     */
    fun merge(props: Map<String, String>) {
        props.forEach { (key, value) -> set(key, value) }
    }

    companion object {
        /** 从文件文本解析。永远成功——无法解析的行按注释保留。 */
        fun parse(text: String): ServerProperties {
            val entries = mutableListOf<PropertiesEntry>()
            // 按 \n 切分；末尾换行会切出一个空串，正好用 Comment("") 表示，
            // 这样 toText() 能把它还原成结尾换行符。
            for (rawLine in text.split('\n')) {
                val line = rawLine.removeSuffix("\r")
                if (line.isEmpty() || line.startsWith("#")) {
                    entries += PropertiesEntry.Comment(line)
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    // 没有 = 或 key 为空：按注释保留原文，不丢内容
                    entries += PropertiesEntry.Comment(line)
                    continue
                }
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1)
                if (key.isEmpty()) {
                    entries += PropertiesEntry.Comment(line)
                } else {
                    entries += PropertiesEntry.KeyValue(key, value)
                }
            }
            return ServerProperties(entries)
        }

        /** 由普通 Map 构造（文件尚不存在、需要凭设置项生成一份新配置时使用）。 */
        fun of(props: Map<String, String>): ServerProperties {
            val entries = mutableListOf<PropertiesEntry>(
                PropertiesEntry.Comment("# Minecraft server properties"),
                PropertiesEntry.Comment("# Generated by MineServeMobile"),
                PropertiesEntry.Comment("# Edit via MineServeMobile UI")
            )
            props.forEach { (key, value) -> entries += PropertiesEntry.KeyValue(key, value) }
            entries += PropertiesEntry.Comment("")
            return ServerProperties(entries)
        }
    }
}
