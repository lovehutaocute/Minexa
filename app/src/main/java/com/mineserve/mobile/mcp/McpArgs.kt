package com.mineserve.mobile.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP 工具调用的参数读取（纯 JVM，可单测）。
 *
 * 单独抽出来的原因：MCP 的 `arguments` 完全由客户端构造，类型并不可信 ——
 * 同一个字段可能是字符串 `"5"`、数字 `5`、也可能是 `null`。
 * 这里统一成「按字符串取，再各自解析」，避免每个工具重复写一遍类型判断，
 * 也避免 `as Int` 这类直接强转在客户端传字符串时抛 ClassCastException。
 */
internal object McpArgs {

    /** 取字符串；字段缺失或为 JSON null 时返回 null（不是 "null" 字面量）。 */
    fun string(args: JsonObject?, key: String): String? =
        (args?.get(key) as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content

    /** 取整数；非数字内容返回 null（由调用方决定是报错还是用默认值）。 */
    fun int(args: JsonObject?, key: String): Int? = string(args, key)?.trim()?.toIntOrNull()

    fun long(args: JsonObject?, key: String): Long? = string(args, key)?.trim()?.toLongOrNull()

    /**
     * 取布尔。接受 true/false、1/0、yes/no、on/off（不区分大小写）。
     * 无法识别时返回 null —— 刻意不默认成 false：
     * 「没传」与「明确传了 false」在 confirm 这类安全开关上语义完全不同。
     */
    fun bool(args: JsonObject?, key: String): Boolean? =
        when (string(args, key)?.trim()?.lowercase()) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> null
        }

    /**
     * 取字符串数组；也接受单个字符串（视为单元素数组）。
     * 元素会去空白并剔除空串，便于 `packages: "wget"` 与 `packages: ["wget","curl"]` 都可用。
     */
    fun stringList(args: JsonObject?, key: String): List<String> {
        val element = args?.get(key) ?: return emptyList()
        val items: List<JsonPrimitive> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonPrimitive }.filter { it !is JsonNull }
            is JsonPrimitive -> if (element is JsonNull) emptyList() else listOf(element)
            else -> emptyList()
        }
        return items
            .flatMap { it.content.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
