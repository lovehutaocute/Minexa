package com.mineserve.mobile.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 文件系统操作的安全封装（目前只有递归删除）。
 *
 * ## 为什么不能用 `File.deleteRecursively()`
 * Kotlin 的实现是 `walkBottomUp()`，而 `FileTreeWalk` 在构造目录状态时有
 * `assert(rootDir.isDirectory) { "rootDir must be verified to be directory beforehand." }`。
 * 只要枚举那一刻根**不是目录**就抛 `AssertionError` —— 注意是 `Error` 不是 `Exception`，
 * `catch (e: Exception)` 拦不住，直接把整个 App 打崩。
 *
 * 公共存储是 FUSE，`exists()` 判定通过之后目录仍可能被并发删掉、被改名，
 * 或者根本就是一个同名普通文件。所以「先 exists() 再 deleteRecursively()」
 * 这种写法在真机上迟早会炸（2026-11-13 实机崩溃就在 `McViewModel.deleteCore`）。
 *
 * ## 这里的实现
 * - 不做任何断言，失败只返回 false；
 * - 不用递归（目录很深时不会爆栈），广度收集 + 逆序删除；
 * - 符号链接只删链接本身，**绝不**跟进目标目录去删人家的内容；
 * - 目录枚举失败（`listFiles()` 返回 null）时保留该目录并返回 false，
 *   由调用方决定怎么提示，而不是假装删干净了；
 * - 已经不在的条目算删除成功（并发删除 / 重复调用都是幂等的）。
 */
object FileOps {

    /**
     * 删除 [target] 及其全部内容。
     *
     * @return true 表示「删完后它确实不存在了」。任何一步删不掉都返回 false。
     */
    fun deleteRecursively(target: File): Boolean {
        if (!existsNoFollow(target)) return true

        var ok = true
        // 逆序：先删子项再删父目录
        for (item in collectForDelete(target).asReversed()) {
            val removed = runCatching { !existsNoFollow(item) || item.delete() }.getOrDefault(false)
            if (!removed) ok = false
        }
        return ok && !existsNoFollow(target)
    }

    /**
     * 与 [deleteRecursively] 行为一致，但返回**实际删掉的条目数**（含目录本身，不含失败的）。
     *
     * MCP 的 `delete_file` 要向调用方回报删了多少个条目，所以不能只给 Boolean。
     */
    fun deleteRecursivelyCounted(target: File): Int {
        if (!existsNoFollow(target)) return 0
        var removed = 0
        for (item in collectForDelete(target).asReversed()) {
            if (runCatching { !existsNoFollow(item) || item.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    /** 广度收集整棵树（不递归，不会爆栈），符号链接只收链接本身、不展开目标 */
    private fun collectForDelete(target: File): List<File> {
        val collected = ArrayList<File>()
        val queue = ArrayDeque<File>()
        queue.addLast(target)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            collected += current
            if (isRealDirectory(current)) {
                // listFiles() 在 FUSE 上偶发返回 null（权限/IO 抖动），当作空目录处理，
                // 后面那次 delete 会因为非空而失败，结果就是 false，不会误报成功。
                runCatching { current.listFiles() }.getOrNull()?.forEach { queue.addLast(it) }
            }
        }
        return collected
    }

    /**
     * 条目是否存在（**不跟随**符号链接）。
     *
     * `File.exists()` 会跟随链接，指向已消失目标的软链恒为 false，
     * 于是「判断-删除」逻辑会以为目标已删除、实际把软链本身留了下来。
     */
    fun existsNoFollow(target: File): Boolean = runCatching {
        Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(target.exists())

    /** 是否真的是目录（软链一律当文件处理，避免顺着链接删到别人的目录里） */
    fun isRealDirectory(target: File): Boolean = runCatching {
        val path = target.toPath()
        !Files.isSymbolicLink(path) && Files.isDirectory(path)
    }.getOrDefault(false)
}
