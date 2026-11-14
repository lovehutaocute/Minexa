package com.mineserve.mobile.data

import android.content.Context
import com.mineserve.mobile.server.JavaWorldGen
import java.util.concurrent.atomic.AtomicReference

/**
 * 原版方块 id 白名单（基岩版 1.26）。
 *
 * **为什么需要**：超平坦层里写错一个方块名，服务端不会报错 —— BDS 会把不认识的那一层
 * 当成空气，世界生成出来就是缺层的；而 `level.dat` 一旦生成，生成参数就改不了了，
 * 用户只能重置世界重来。所以在**创建世界之前**就把名字对上。
 *
 * 数据来源：`Mojang/bedrock-samples` 的
 * `metadata/vanilladata_modules/mojang-blocks.json`（官方随包数据），
 * 提取 `data_items[].name` 存成 `assets/bedrock_block_ids.txt`，一行一个。
 * 升级 BDS 时整份替换该文件即可。
 *
 * 只校验 `minecraft:` 命名空间的方块：别的命名空间必然是用户自己装的行为包，
 * 我们拿不到权威清单，硬拦只会误伤。
 */
object BlockIds {

    private const val ASSET = "bedrock_block_ids.txt"

    private val cache = AtomicReference<Set<String>?>(null)

    fun all(context: Context): Set<String> {
        cache.get()?.let { return it }
        val loaded = runCatching {
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toHashSet()
            }
        }.getOrDefault(emptySet())
        cache.set(loaded)
        return loaded
    }

    /**
     * 返回**不认识的原版方块 id**（已规范化成 `minecraft:xxx`）。
     *
     * 清单读不到、或 id 属于非 `minecraft` 命名空间时一律放行（返回里不含它）；
     * 调用方拿到非空结果就应当拒绝创建并告诉用户是哪几个。
     */
    fun unknownVanilla(context: Context, ids: List<String>): List<String> {
        val known = all(context)
        if (known.isEmpty()) return emptyList()
        return ids.mapNotNull { raw ->
            val normalized = JavaWorldGen.normalizeId(raw)
                ?: "minecraft:" + raw.trim().lowercase().removePrefix("minecraft:")
            if (!normalized.startsWith("minecraft:")) return@mapNotNull null
            if (normalized in known) null else normalized
        }.distinct()
    }
}
