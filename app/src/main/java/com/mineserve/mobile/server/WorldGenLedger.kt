package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 「这个世界的生成参数是用户选的什么」—— 建世界时自己记一份，放 `服务端目录/.minexa/world-gen.json`。
 *
 * 为什么需要它：两版的生成参数都**只写进内核**，而且写进去之后不一定读得回来。
 *
 * - 基岩版：我们预写的 `level.dat` 只有 5 个字段；服务端启动后会把 `level.dat` 整体重写。
 *   若用户留空种子，`RandomSeed` 会被服务端随机生成 —— 此时"用户想要什么"已经查不到了。
 * - Java 版：参数只在 `server.properties`，且**世界一旦生成就失效**；世界目录还没生成时
 *   `level.dat` 根本不存在，UI 只能显示"未知"。
 *
 * 所以这里只存**用户输入**（原文种子 + 生成方式 + 时间），不存任何推导结果：
 * 它永远是"当时的要求"，不冒充"世界的真实状态"。真实状态一律以 `level.dat` 为准，
 * UI 也必须把两者分开显示（"待生成" vs "已生成"）。
 */
object WorldGenLedger {

    private const val DIR = ".minexa"
    private const val FILE = "world-gen.json"

    /** 一种核心一套写法：`bds` 用 code（1/2/5），`java` 用 `level-type` 的 id。 */
    enum class Kind { BDS, JAVA }

    /**
     * @param seedText 用户当时填的种子原文（空串 = 随机）
     * @param generator 生成方式：BDS 是 code 的字符串（`1`/`2`/`5`），Java 是 `minecraft:flat` 这种 id
     */
    data class Record(val seedText: String, val generator: String, val kind: Kind, val createdAt: Long)

    fun file(serverDir: File): File = File(File(serverDir, DIR), FILE)

    /** 读整张表；文件不存在/坏掉一律返回空表（这是辅助信息，不能因为它读失败就挡住世界管理）。 */
    fun read(serverDir: File): Map<String, Record> {
        val f = file(serverDir)
        if (!f.isFile) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject }
            .getOrNull() ?: return emptyMap()
        val worlds = root["worlds"] as? JsonObject ?: return emptyMap()
        return worlds.mapNotNull { (name, element) ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val kind = when (o["kind"]?.jsonPrimitive?.contentOrNull) {
                "java" -> Kind.JAVA
                else -> Kind.BDS
            }
            val record = Record(
                seedText = o["seed"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                generator = o["generator"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                kind = kind,
                createdAt = o["at"]?.jsonPrimitive?.longOrNull ?: 0L
            )
            name to record
        }.toMap()
    }

    fun get(serverDir: File, worldName: String): Record? =
        read(serverDir)[Worlds.sanitize(worldName)]

    /** 写一条记录（同名的会被覆盖）。失败只影响提示文案，返回 false 由调用方决定是否提示。 */
    fun put(serverDir: File, worldName: String, record: Record): Boolean = runCatching {
        val name = Worlds.sanitize(worldName)
        if (name.isBlank()) return false
        val all = read(serverDir).toMutableMap()
        all[name] = record
        write(serverDir, all)
    }.getOrDefault(false)

    /** 世界被删除/重置时同步删掉记录，避免下次同名世界读到上一轮的要求。 */
    fun remove(serverDir: File, worldName: String): Boolean = runCatching {
        val name = Worlds.sanitize(worldName)
        val all = read(serverDir).toMutableMap()
        if (all.remove(name) == null) return true
        write(serverDir, all)
    }.getOrDefault(false)

    /** 世界被改名时跟着改名。 */
    fun rename(serverDir: File, oldName: String, newName: String): Boolean = runCatching {
        val from = Worlds.sanitize(oldName)
        val to = Worlds.sanitize(newName)
        val all = read(serverDir).toMutableMap()
        val record = all.remove(from) ?: return true
        all[to] = record
        write(serverDir, all)
    }.getOrDefault(false)

    private fun write(serverDir: File, all: Map<String, Record>): Boolean {
        val f = file(serverDir)
        f.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put(
                "worlds",
                buildJsonObject {
                    all.forEach { (name, r) ->
                        put(
                            name,
                            buildJsonObject {
                                put("seed", r.seedText)
                                put("generator", r.generator)
                                put("kind", if (r.kind == Kind.JAVA) "java" else "bds")
                                put("at", r.createdAt)
                            }
                        )
                    }
                }
            )
        }
        // 先写临时文件再改名：这份文件只是提示用，但半截 JSON 会让下次读到空表
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(Json.encodeToString(JsonObject.serializer(), json), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        return true
    }

    /** 给 UI 直接用的展示值：解析不了就返回 null（让 UI 显示"未知"而不是 0）。 */
    fun bdsGeneratorCode(record: Record?): Int? =
        if (record?.kind == Kind.BDS) record.generator.toIntOrNull() else null

    fun javaGeneratorId(record: Record?): String? =
        if (record?.kind == Kind.JAVA) record.generator.takeIf { it.isNotBlank() } else null
}
