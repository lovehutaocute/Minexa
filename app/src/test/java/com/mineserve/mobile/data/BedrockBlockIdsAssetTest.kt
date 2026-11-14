package com.mineserve.mobile.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `assets/bedrock_block_ids.txt` 的守门测试。
 *
 * 这份清单是从 `Mojang/bedrock-samples` 的 `mojang-blocks.json` 生成的静态资源，
 * 升级 BDS 时会整份替换。它一旦被误删/写空/少了 `minecraft:` 前缀，
 * 校验会静默失效（不拦任何东西），所以在这里钉死几条底线。
 */
class BedrockBlockIdsAssetTest {

    private fun asset(): File {
        // 单测的工作目录是 app 模块根目录
        val f = File("src/main/assets/bedrock_block_ids.txt")
        assertTrue(f.isFile, "缺少 assets/bedrock_block_ids.txt")
        return f
    }

    private fun ids(): List<String> =
        asset().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `清单规模合理`() {
        val names = ids()
        // 1.26 的 vanilla 方块一千四百多；低于 1000 说明文件被截断
        assertTrue(names.size > 1000, "方块清单只有 ${names.size} 条，疑似被截断")
    }

    @Test
    fun `全部带 minecraft 命名空间且无重复`() {
        val names = ids()
        assertTrue(names.all { it.startsWith("minecraft:") }, "存在不带 minecraft: 前缀的条目")
        assertTrue(names.size == names.toSet().size, "清单里有重复条目")
    }

    @Test
    fun `关键方块都在`() {
        val set = ids().toSet()
        listOf(
            "minecraft:bedrock", "minecraft:dirt", "minecraft:grass_block",
            "minecraft:stone", "minecraft:air", "minecraft:glass"
        ).forEach { assertTrue(it in set, "清单缺少 $it") }
    }
}
