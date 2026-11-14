package com.mineserve.mobile.server

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Bedrock 世界 `level.dat` 的最小读写实现（**小端 NBT**，8 字节文件头）。
 *
 * 为什么必须自己写：
 * - 它是基岩版**唯一**存放世界种子与生成方式的地方（`server.properties` 里只有 `level-seed`，
 *   且只对"尚未生成"的世界生效）。
 * - 官方没有任何工具/命令能改 `Generator` 或 `FlatWorldLayers`，Minexa 若要提供
 *   「新世界选超平坦 / 虚空 / 自定义层」，只能直接改这个文件。
 *
 * 文件结构（实测 1.19.83 ~ 1.26.63 完全一致，见 `docs/BDS-ADDONS-WORLDS-VERIFICATION.md`）：
 * ```
 * offset 0  : int32 LE  version   （常见 10）
 * offset 4  : int32 LE  length    （其后 NBT 负载的字节数）
 * offset 8  : TAG_Compound 根节点
 * ```
 *
 * 设计取舍：不重建文件，只**就地改值**——解析成带类型的节点树，改动目标字段后按原结构
 * 重新序列化，其它字段（含未知的、将来新增的）一个不动。写盘走「临时文件 + 备份 + 改名」，
 * 避免写到一半掉电把世界搞坏。
 */
object LevelDat {

    // ── NBT 标签 ────────────────────────────────────────────────

    /** NBT 标签类型编号（与规范一致）。 */
    object Type {
        const val END = 0
        const val BYTE = 1
        const val SHORT = 2
        const val INT = 3
        const val LONG = 4
        const val FLOAT = 5
        const val DOUBLE = 6
        const val BYTE_ARRAY = 7
        const val STRING = 8
        const val LIST = 9
        const val COMPOUND = 10
        const val INT_ARRAY = 11
        const val LONG_ARRAY = 12
    }

    sealed class Tag(val type: Int) {
        class ByteTag(var value: Int) : Tag(Type.BYTE)
        class ShortTag(var value: Int) : Tag(Type.SHORT)
        class IntTag(var value: Int) : Tag(Type.INT)
        class LongTag(var value: Long) : Tag(Type.LONG)
        class FloatTag(var value: Float) : Tag(Type.FLOAT)
        class DoubleTag(var value: Double) : Tag(Type.DOUBLE)
        class ByteArrayTag(val value: ByteArray) : Tag(Type.BYTE_ARRAY)
        class StringTag(var value: String) : Tag(Type.STRING)
        class IntArrayTag(val value: IntArray) : Tag(Type.INT_ARRAY)
        class LongArrayTag(val value: LongArray) : Tag(Type.LONG_ARRAY)

        /** 列表自带元素类型；空列表的元素类型也必须原样保留（BDS 用 0 表示）。 */
        class ListTag(var elementType: Int, val items: MutableList<Tag> = mutableListOf()) : Tag(Type.LIST)

        /** 保留插入顺序：重新序列化后文件差异最小。 */
        class CompoundTag(val entries: LinkedHashMap<String, Tag> = LinkedHashMap()) : Tag(Type.COMPOUND) {
            operator fun get(name: String): Tag? = entries[name]
            operator fun set(name: String, tag: Tag) { entries[name] = tag }
        }
    }

    /** 解析结果。 */
    data class World(
        val headerVersion: Int,
        val rootName: String,
        val root: Tag.CompoundTag
    ) {
        fun long(name: String): Long? = (root[name] as? Tag.LongTag)?.value
        fun int(name: String): Int? = (root[name] as? Tag.IntTag)?.value
        fun string(name: String): String? = (root[name] as? Tag.StringTag)?.value
        fun byte(name: String): Int? = (root[name] as? Tag.ByteTag)?.value

        /** 世界种子（`RandomSeed`，int64；文本种子在 BDS 侧已被哈希成整数）。 */
        var seed: Long?
            get() = long("RandomSeed")
            set(v) {
                if (v == null) root.entries.remove("RandomSeed") else root["RandomSeed"] = Tag.LongTag(v)
            }

        /** 生成器：1=普通，2=超平坦，5=虚空（实测 1.19 起一致）。 */
        var generator: Int?
            get() = int("Generator")
            set(v) {
                if (v == null) root.entries.remove("Generator") else root["Generator"] = Tag.IntTag(v)
            }

        /** 世界显示名（与目录名无关，BDS **不会**因为改目录名而同步它）。 */
        var levelName: String?
            get() = string("LevelName")
            set(v) {
                if (v == null) root.entries.remove("LevelName") else root["LevelName"] = Tag.StringTag(v)
            }

        /**
         * 是否**已被服务端真正生成过**。
         *
         * 判据只认服务端自己才会写的字段（`InventoryVersion` / `NetworkVersion`）。
         * Minexa 新建世界时会预写一份只含 5 个字段的 `level.dat` —— 那是"用户的要求"，
         * 不是世界状态；把它当成已生成，UI 就会对着一个还没生成的世界报种子/地形。
         */
        val generatedByServer: Boolean
            get() = string("InventoryVersion") != null || int("NetworkVersion") != null

        /** 超平坦层配置（JSON 字符串）。 */
        var flatWorldLayers: String?
            get() = string("FlatWorldLayers")
            set(v) {
                if (v == null) root.entries.remove("FlatWorldLayers") else root["FlatWorldLayers"] = Tag.StringTag(v)
            }
    }

    // ── 读取 ────────────────────────────────────────────────────

    /** 读 `level.dat`；文件不存在/格式不认识返回 null（调用方按"没有世界"处理，不要抛）。 */
    fun read(file: File): World? = runCatching {
        val raw = file.readBytes()
        if (raw.size < 9) return null
        val headerVersion = readIntLe(raw, 0)
        val declared = readIntLe(raw, 4)
        // 正常情况 length 与实际一致；不一致时（个别第三方工具写的）以实际剩余字节为准。
        val payloadLen = if (declared in 1..(raw.size - 8)) declared else raw.size - 8
        val payload = raw.copyOfRange(8, 8 + payloadLen)
        val r = NbtReader(payload)
        val type = r.u8()
        if (type != Type.COMPOUND) return null
        val rootName = r.str()
        val root = r.compoundBody() as Tag.CompoundTag
        World(headerVersion, rootName, root)
    }.getOrNull()

    /** 只取种子，读失败返回 null。 */
    fun readSeed(file: File): Long? = read(file)?.seed

    // ── 写入 ────────────────────────────────────────────────────

    /**
     * 写回 `level.dat`。
     *
     * @param backup 为 true 时把原文件另存为 `level.dat.minexa-bak`（失败可手工恢复）
     * @return 是否写成功；失败时磁盘上的原文件不变
     */
    fun write(file: File, world: World, backup: Boolean = true): Boolean = runCatching {
        val payload = NbtWriter().apply {
            u8(Type.COMPOUND)
            str(world.rootName)
            compoundBody(world.root)
        }.toByteArray()

        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(intLe(world.headerVersion.takeIf { it > 0 } ?: 10))
        out.write(intLe(payload.size))
        out.write(payload)

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out.toByteArray())
        if (backup && file.exists()) {
            file.copyTo(File(file.parentFile, file.name + ".minexa-bak"), overwrite = true)
        }
        // File.renameTo 在 Android 同一分区上是原子的；失败退回复制，避免留下 .tmp
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /** 改一个/多个字段后写回，读不到原文时直接返回 false（绝不凭空造整个文件）。 */
    fun update(
        file: File,
        backup: Boolean = true,
        block: (World) -> Unit
    ): Boolean {
        val world = read(file) ?: return false
        block(world)
        return write(file, world, backup)
    }

    // ── 字节工具（全部小端） ────────────────────────────────────

    private fun readIntLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun intLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private class NbtReader(private val b: ByteArray) {
        private var i = 0

        fun u8(): Int = b[i++].toInt() and 0xFF

        private fun u16(): Int {
            val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            return v
        }

        private fun i32(): Int {
            val v = (b[i].toInt() and 0xFF) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            return v
        }

        private fun i64(): Long {
            var v = 0L
            for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
            i += 8
            return v
        }

        fun str(): String {
            val len = u16()
            val s = String(b, i, len, Charsets.UTF_8)
            i += len
            return s
        }

        /** 从「类型字节」之后开始读，返回 Compound；调用方需已读入类型与名字。 */
        fun compoundBody(): Tag {
            val c = Tag.CompoundTag()
            while (true) {
                val t = u8()
                if (t == Type.END) break
                val name = str()
                c[name] = payload(t)
            }
            return c
        }

        fun payload(t: Int): Tag = when (t) {
            Type.BYTE -> Tag.ByteTag(u8())
            Type.SHORT -> Tag.ShortTag(u16())
            Type.INT -> Tag.IntTag(i32())
            Type.LONG -> Tag.LongTag(i64())
            Type.FLOAT -> Tag.FloatTag(Float.fromBits(i32()))
            Type.DOUBLE -> Tag.DoubleTag(Double.fromBits(i64()))
            Type.BYTE_ARRAY -> {
                val n = i32()
                val v = b.copyOfRange(i, i + n)
                i += n
                Tag.ByteArrayTag(v)
            }
            Type.STRING -> Tag.StringTag(str())
            Type.LIST -> {
                val et = u8()
                val n = i32()
                val items = ArrayList<Tag>(n)
                for (k in 0 until n) items.add(payload(et))
                Tag.ListTag(et, items)
            }
            Type.COMPOUND -> compoundBody()
            Type.INT_ARRAY -> {
                val n = i32()
                val v = IntArray(n) { i32() }
                Tag.IntArrayTag(v)
            }
            Type.LONG_ARRAY -> {
                val n = i32()
                val v = LongArray(n) { i64() }
                Tag.LongArrayTag(v)
            }
            else -> throw IllegalStateException("unknown NBT tag $t")
        }
    }

    private class NbtWriter {
        private val out = ByteArrayOutputStream(4096)

        fun toByteArray(): ByteArray = out.toByteArray()

        fun u8(v: Int) { out.write(v and 0xFF) }

        private fun i16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        private fun i32(v: Int) {
            out.write(intLe(v))
        }

        private fun i64(v: Long) {
            for (k in 0 until 8) out.write(((v ushr (8 * k)) and 0xFF).toInt())
        }

        fun str(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            i16(bytes.size)
            out.write(bytes)
        }

        fun compoundBody(c: Tag.CompoundTag) {
            for ((name, tag) in c.entries) {
                u8(tag.type)
                str(name)
                payload(tag)
            }
            u8(Type.END)
        }

        fun payload(tag: Tag) {
            when (tag) {
                is Tag.ByteTag -> u8(tag.value)
                is Tag.ShortTag -> i16(tag.value)
                is Tag.IntTag -> i32(tag.value)
                is Tag.LongTag -> i64(tag.value)
                is Tag.FloatTag -> i32(tag.value.toRawBits())
                is Tag.DoubleTag -> i64(tag.value.toRawBits())
                is Tag.ByteArrayTag -> {
                    i32(tag.value.size)
                    out.write(tag.value)
                }
                is Tag.StringTag -> str(tag.value)
                is Tag.ListTag -> {
                    u8(tag.elementType)
                    i32(tag.items.size)
                    tag.items.forEach { payload(it) }
                }
                is Tag.CompoundTag -> compoundBody(tag)
                is Tag.IntArrayTag -> {
                    i32(tag.value.size)
                    tag.value.forEach { i32(it) }
                }
                is Tag.LongArrayTag -> {
                    i32(tag.value.size)
                    tag.value.forEach { i64(it) }
                }
            }
        }
    }
}
