package com.mineserve.mobile.server

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * **Java 版**世界 `level.dat` 的读写（gzip + **大端** NBT）。
 *
 * 与基岩版（[LevelDat]，小端、无压缩）的两处根本差异：
 * 1. 整个文件是 gzip 流，解压后才是 NBT；
 * 2. 所有多字节整数是**大端**。
 *
 * 结构：根 TAG_Compound → `Data`（TAG_Compound）→ 世界字段。
 * 1.21 及以前：`RandomSeed` / `GameRules` / `WorldGenSettings` / `Difficulty` 都在 `Data` 里；
 * **26.1 起重构**：种子与生成设置搬到 `world/data/minecraft/world_gen_settings.dat`，
 * 游戏规则搬到 `game_rules.dat`，难度改成 `Data.difficulty_settings` 子复合。
 * 这些分支由 [JavaWorlds] 处理，本类只负责"把文件读成节点树 / 原样写回"。
 *
 * 设计取舍与基岩版一致：**不重建文件**，解析成节点树后只改目标字段再整体序列化，
 * 写盘走「临时文件 + 备份 + 改名」。
 */
object JavaLevelDat {

    /** 读取结果：根复合 + 其中的 `Data` 复合。 */
    data class World(val root: LevelDat.Tag.CompoundTag) {
        val data: LevelDat.Tag.CompoundTag? get() = root["Data"] as? LevelDat.Tag.CompoundTag
    }

    /** 读 `level.dat`；文件不存在、不是 gzip、NBT 结构不认识时返回 null（调用方按"读不到"处理）。 */
    fun read(file: File): World? = runCatching {
        if (!file.isFile) return null
        val raw = GZIPInputStream(file.inputStream()).use { it.readBytes() }
        if (raw.size < 3) return null
        val r = Reader(raw)
        val type = r.u8()
        if (type != LevelDat.Type.COMPOUND) return null
        r.str() // 根节点名（通常为空串）
        World(r.compoundBody() as LevelDat.Tag.CompoundTag)
    }.getOrNull()

    /** 读世界目录下的 `level.dat`。 */
    fun readWorld(worldDir: File): World? = read(File(worldDir, Worlds.LEVEL_DAT))

    /** 只取 `Data` 复合。 */
    fun readData(worldDir: File): LevelDat.Tag.CompoundTag? = readWorld(worldDir)?.data

    /**
     * 写回 `level.dat`。
     *
     * @param backup 为 true 时先把原文件另存为 `level.dat.minexa-bak`
     */
    fun write(file: File, world: World, backup: Boolean = true): Boolean = runCatching {
        val payload = Writer().apply {
            u8(LevelDat.Type.COMPOUND)
            str("")
            compoundBody(world.root)
        }.toByteArray()

        val out = ByteArrayOutputStream(payload.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(payload) }

        val tmp = File(file.parentFile, file.name + ".minexa-tmp")
        tmp.writeBytes(out.toByteArray())
        if (backup && file.exists()) {
            file.copyTo(File(file.parentFile, file.name + ".minexa-bak"), overwrite = true)
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /** 读 → 改 → 写；读不到时直接失败（**绝不凭空造一份 level.dat**）。 */
    fun update(worldDir: File, backup: Boolean = true, block: (LevelDat.Tag.CompoundTag) -> Unit): Boolean {
        val file = File(worldDir, Worlds.LEVEL_DAT)
        val world = read(file) ?: return false
        block(world.root)
        return write(file, world, backup)
    }

    // ── 便捷取值 ────────────────────────────────────────────────

    fun string(tag: LevelDat.Tag?, name: String): String? =
        ((tag as? LevelDat.Tag.CompoundTag)?.get(name) as? LevelDat.Tag.StringTag)?.value

    fun long(tag: LevelDat.Tag?, name: String): Long? =
        ((tag as? LevelDat.Tag.CompoundTag)?.get(name) as? LevelDat.Tag.LongTag)?.value

    fun int(tag: LevelDat.Tag?, name: String): Int? =
        ((tag as? LevelDat.Tag.CompoundTag)?.get(name) as? LevelDat.Tag.IntTag)?.value

    /** 读整数：Byte / Short / Int 都接受（同一个字段在不同版本里类型会变）。 */
    fun intAny(tag: LevelDat.Tag?, name: String): Int? {
        val t = (tag as? LevelDat.Tag.CompoundTag)?.get(name) ?: return null
        return when (t) {
            is LevelDat.Tag.IntTag -> t.value
            is LevelDat.Tag.ShortTag -> t.value
            is LevelDat.Tag.ByteTag -> t.value
            else -> null
        }
    }

    fun bool(tag: LevelDat.Tag?, name: String): Boolean? =
        ((tag as? LevelDat.Tag.CompoundTag)?.get(name) as? LevelDat.Tag.ByteTag)?.value?.let { it != 0 }

    fun compound(tag: LevelDat.Tag?, name: String): LevelDat.Tag.CompoundTag? =
        (tag as? LevelDat.Tag.CompoundTag)?.get(name) as? LevelDat.Tag.CompoundTag

    fun stringList(tag: LevelDat.Tag, name: String): List<String> =
        ((tag as? LevelDat.Tag.CompoundTag)?.get(name) as? LevelDat.Tag.ListTag)?.items
            ?.mapNotNull { (it as? LevelDat.Tag.StringTag)?.value }
            .orEmpty()

    // ── 大端 NBT 读写（与 LevelDat 的小端实现对称） ──────────────

    private class Reader(private val b: ByteArray) {
        private var i = 0

        fun u8(): Int = b[i++].toInt() and 0xFF

        private fun u16(): Int {
            val v = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
            return v
        }

        private fun i32(): Int {
            val v = ((b[i].toInt() and 0xFF) shl 24) or
                ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or
                (b[i + 3].toInt() and 0xFF)
            i += 4
            return v
        }

        private fun i64(): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
            i += 8
            return v
        }

        fun str(): String {
            val len = u16()
            val s = String(b, i, len, Charsets.UTF_8)
            i += len
            return s
        }

        fun compoundBody(): LevelDat.Tag {
            val c = LevelDat.Tag.CompoundTag()
            while (true) {
                val t = u8()
                if (t == LevelDat.Type.END) break
                val name = str()
                c[name] = payload(t)
            }
            return c
        }

        fun payload(t: Int): LevelDat.Tag = when (t) {
            LevelDat.Type.BYTE -> LevelDat.Tag.ByteTag(u8())
            LevelDat.Type.SHORT -> LevelDat.Tag.ShortTag(u16())
            LevelDat.Type.INT -> LevelDat.Tag.IntTag(i32())
            LevelDat.Type.LONG -> LevelDat.Tag.LongTag(i64())
            LevelDat.Type.FLOAT -> LevelDat.Tag.FloatTag(Float.fromBits(i32()))
            LevelDat.Type.DOUBLE -> LevelDat.Tag.DoubleTag(Double.fromBits(i64()))
            LevelDat.Type.BYTE_ARRAY -> {
                val n = i32(); val v = b.copyOfRange(i, i + n); i += n
                LevelDat.Tag.ByteArrayTag(v)
            }
            LevelDat.Type.STRING -> LevelDat.Tag.StringTag(str())
            LevelDat.Type.LIST -> {
                val et = u8()
                val n = i32()
                val items = ArrayList<LevelDat.Tag>(n)
                for (k in 0 until n) items.add(payload(et))
                LevelDat.Tag.ListTag(et, items)
            }
            LevelDat.Type.COMPOUND -> compoundBody()
            LevelDat.Type.INT_ARRAY -> {
                val n = i32(); LevelDat.Tag.IntArrayTag(IntArray(n) { i32() })
            }
            LevelDat.Type.LONG_ARRAY -> {
                val n = i32(); LevelDat.Tag.LongArrayTag(LongArray(n) { i64() })
            }
            else -> throw IllegalStateException("unknown NBT tag $t")
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream(4096)

        fun toByteArray(): ByteArray = out.toByteArray()

        fun u8(v: Int) { out.write(v and 0xFF) }

        private fun i16(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        private fun i32(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        private fun i64(v: Long) {
            for (k in 7 downTo 0) out.write(((v ushr (8 * k)) and 0xFF).toInt())
        }

        fun str(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            i16(bytes.size)
            out.write(bytes)
        }

        fun compoundBody(c: LevelDat.Tag.CompoundTag) {
            for ((name, tag) in c.entries) {
                u8(tag.type)
                str(name)
                payload(tag)
            }
            u8(LevelDat.Type.END)
        }

        fun payload(tag: LevelDat.Tag) {
            when (tag) {
                is LevelDat.Tag.ByteTag -> u8(tag.value)
                is LevelDat.Tag.ShortTag -> i16(tag.value)
                is LevelDat.Tag.IntTag -> i32(tag.value)
                is LevelDat.Tag.LongTag -> i64(tag.value)
                is LevelDat.Tag.FloatTag -> i32(tag.value.toRawBits())
                is LevelDat.Tag.DoubleTag -> i64(tag.value.toRawBits())
                is LevelDat.Tag.ByteArrayTag -> {
                    i32(tag.value.size); out.write(tag.value)
                }
                is LevelDat.Tag.StringTag -> str(tag.value)
                is LevelDat.Tag.ListTag -> {
                    u8(tag.elementType)
                    i32(tag.items.size)
                    tag.items.forEach { payload(it) }
                }
                is LevelDat.Tag.CompoundTag -> compoundBody(tag)
                is LevelDat.Tag.IntArrayTag -> {
                    i32(tag.value.size); tag.value.forEach { i32(it) }
                }
                is LevelDat.Tag.LongArrayTag -> {
                    i32(tag.value.size); tag.value.forEach { i64(it) }
                }
            }
        }
    }
}
