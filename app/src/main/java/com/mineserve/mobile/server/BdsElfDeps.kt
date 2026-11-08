package com.mineserve.mobile.server

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * 读 ELF 的动态段，得到 `DT_NEEDED` / `DT_SONAME`，并据此修「文件名与 SONAME 不一致」。
 *
 * ## 为什么需要
 *
 * BDS 1.21 之前是**动态链接**的，安装包里自带一批 `.so`。Mojang 用的构建环境是
 * Debian 系的 gnutls 版 libcurl：文件的字面名字叫 `libcurl.so.4`，但它 ELF 头里
 * 写的 SONAME 是 `libcurl-gnutls.so.4`。正常发行版里 `libcurl4` 包会同时提供这两个
 * 名字（一个软链），而**直接拷文件**的安装包只带来一个名字。
 *
 * 结果是 box64 加载 `libcurl.so.4` 成功后，按 SONAME 再去找
 * `libcurl-gnutls.so.4`，找不到就整条依赖链失败：
 * ```
 * [BOX64] Error initializing native libcurl.so.4 (last dlerror is libcurl-gnutls.so.4: ...)
 * [BOX64] Error: Loading needed libs in elf /opt/minexa-bds/run/<dir>/bedrock_server
 * ```
 * 这段报错把它伪装成「主程序缺库」，实际只差一个同内容不同名的软链。
 *
 * ## 做法
 *
 * 不写死任何库名：启动前扫一遍运行目录里的所有 `.so`，读出 SONAME，
 * 名字不一致就补一个指向它的软链。`libssl.so.1.1`、`libcrypto.so.1.1` 这类
 * 同一个毛病一次全修掉；1.21+ 的静态包没有 `.so`，这段什么都不做。
 */
object BdsElfDeps {

    /** 动态段里我们关心的两项 */
    data class DynInfo(val soname: String?, val needed: List<String>)

    /** 缓存：同一个文件在一次启动里会被问多次（别名 + 依赖检查） */
    private val cache = HashMap<String, DynInfo?>()

    /**
     * 解析 ELF64 小端的 `DT_NEEDED` / `DT_SONAME`。
     *
     * 只支持 64 位小端：x86_64 与 aarch64 都是这个布局，而本对象只处理 x86_64 的
     * BDS 运行库。解析失败（不是 ELF、是脚本、被截断）一律返回 null 而不是抛异常 ——
     * 调用点在启动路径上，任何一条坏文件都不该阻止服务器启动。
     */
    fun read(file: File): DynInfo? {
        val key = file.absolutePath + "@" + file.length()
        synchronized(cache) { cache[key]?.let { return it } }
        val info = runCatching { parse(file) }.getOrNull()
        synchronized(cache) { cache[key] = info }
        return info
    }

    private fun parse(file: File): DynInfo? {
        if (!file.isFile || file.length() < 64) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(64)
            raf.readFully(header)
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return null
            if (header[4].toInt() != 2 || header[5].toInt() != 1) return null // 仅 ELF64 / 小端

            val phOff = readLong(header, 0x20)
            val phEntSize = readU16(header, 0x36)
            val phNum = readU16(header, 0x38)
            if (phOff <= 0 || phEntSize < 56 || phNum <= 0) return null

            var dynamicOff = -1L
            var dynamicSize = 0L
            val loads = mutableListOf<Triple<Long, Long, Long>>() // vaddr, offset, filesz
            val ph = ByteArray(phEntSize)
            for (i in 0 until phNum) {
                raf.seek(phOff + i.toLong() * phEntSize)
                raf.readFully(ph, 0, phEntSize)
                val type = readInt(ph, 0)
                val offset = readLong(ph, 8)
                val vaddr = readLong(ph, 16)
                val filesz = readLong(ph, 32)
                when (type) {
                    2 -> { dynamicOff = offset; dynamicSize = filesz } // PT_DYNAMIC
                    1 -> loads += Triple(vaddr, offset, filesz)        // PT_LOAD
                }
            }
            if (dynamicOff < 0 || dynamicSize <= 0) return null

            var strTabVaddr = -1L
            var strTabSize = 0L
            val neededOffsets = mutableListOf<Long>()
            var sonameOffset = -1L
            val entry = ByteArray(16)
            var cursor = 0L
            while (cursor < dynamicSize) {
                raf.seek(dynamicOff + cursor)
                if (raf.read(entry, 0, 16) != 16) break
                val tag = readLong(entry, 0)
                val value = readLong(entry, 8)
                when (tag) {
                    0L -> break
                    1L -> neededOffsets += value
                    5L -> strTabVaddr = value
                    10L -> strTabSize = value
                    14L -> sonameOffset = value
                }
                cursor += 16
            }
            if (strTabVaddr < 0) return null

            // 动态段里 store 的是**虚拟地址**，必须经 PT_LOAD 换算成文件偏移；
            // 直接当 offset 用在带独立 .rodata 段的库上会读到垃圾字符串。
            val strTabFileOff = loads.firstOrNull { (vaddr, _, size) ->
                strTabVaddr >= vaddr && strTabVaddr < vaddr + size
            }?.let { (vaddr, offset, _) -> strTabVaddr - vaddr + offset }
                ?: strTabVaddr

            fun readString(offset: Long): String? {
                if (offset < 0 || (strTabSize in 1..(1 shl 20) && offset >= strTabSize)) return null
                raf.seek(strTabFileOff + offset)
                val out = StringBuilder()
                val buf = ByteArray(1)
                while (out.length < 256) {
                    if (raf.read(buf, 0, 1) != 1) break
                    if (buf[0].toInt() == 0) break
                    out.append((buf[0].toInt() and 0xFF).toChar())
                }
                return out.toString().takeIf { it.isNotBlank() }
            }

            val needed = neededOffsets.mapNotNull { readString(it) }
            val soname = if (sonameOffset >= 0) readString(sonameOffset) else null
            return DynInfo(soname, needed)
        }
    }

    /**
     * 一个可供查找运行库的目录：[dir] 是宿主路径，[guestDir] 是它在容器里的路径
     * （创建软链时要用 guest 路径，宿主路径在 proot 里不存在）。
     */
    data class LibDir(val dir: File, val guestDir: String)

    /**
     * 给运行目录里的每个 `.so` 补一条「SONAME 名字」的软链。
     *
     * 只在**原名字与 SONAME 不一致、且 SONAME 名字还不存在**时创建，
     * 所以重复启动是幂等的，也不会覆盖包自带的文件。
     *
     * @return 新建的 `别名 → 真实文件名`
     */
    fun ensureSonameAliases(runDir: File, guestRunDir: String): List<Pair<String, String>> =
        repairAliases(
            entry = File(runDir, BdsRuntime.ENTRY_NAME),
            runDir = runDir,
            guestRunDir = guestRunDir,
            searchDirs = emptyList()
        )

    /**
     * 完整版：按 `bedrock_server` 的 `DT_NEEDED` 逐个找提供者，再给每个提供者
     * 补它自己 SONAME 的别名。
     *
     * 必须连**容器里的库**一起看：真机上 `libcurl.so.4` 可能来自容器
     * （`/usr/lib/x86_64-linux-gnu`，PRoot 的默认搜索路径里本来就有它），
     * 此时只扫运行目录找不到任何东西，别名也就补不出来 ——
     * 而 box64 报的正是「加载了 libcurl.so.4，却按 SONAME 找不到
     * libcurl-gnutls.so.4」。
     */
    fun repairAliases(
        entry: File,
        runDir: File,
        guestRunDir: String,
        searchDirs: List<LibDir>
    ): List<Pair<String, String>> {
        val dirs = listOf(LibDir(runDir, guestRunDir)) + searchDirs
        // 每个目录只列一次：130 个依赖逐个 listFiles 会在低端机上明显拖慢启动
        val listings = dirs.associateWith { d ->
            d.dir.listFiles()?.filter { it.isFile }?.associateBy { it.name } ?: emptyMap()
        }
        val candidates = LinkedHashMap<String, Pair<File, String>>() // 别名候选：文件名 → (宿主文件, guest 路径)

        fun addCandidate(dir: LibDir, file: File) {
            val name = file.name
            if (!isSharedLib(name)) return
            if (candidates.containsKey(name)) return
            candidates[name] = file to "${dir.guestDir.trimEnd('/')}/$name"
        }

        // ① 运行目录里已经链好的所有库：它们最可能被 dlopen（含主程序的直接依赖）
        dirs.first().let { d -> listings[d]?.values?.forEach { addCandidate(d, it) } }

        // ② 主程序声明依赖的每一个库，找出它在哪个目录（含容器）
        val deps = read(entry)?.needed.orEmpty().filter { it.contains(".so") }
        deps.forEach { dep ->
            dirs.forEach { d ->
                listings[d]?.get(dep)?.let { addCandidate(d, it) }
            }
        }

        val created = mutableListOf<Pair<String, String>>()
        candidates.forEach { (name, provider) ->
            val (file, guestPath) = provider
            val soname = read(file)?.soname ?: return@forEach
            if (soname.isBlank() || soname == name) return@forEach
            if (!isSharedLib(soname)) return@forEach
            val alias = File(runDir, soname)
            if (alias.exists()) return@forEach
            // 优先软链（0 额外空间）；极少数文件系统/权限不允许建软链时退回复制。
            // 复制也只有几百 KB ~ 几 MB，比「启动失败」便宜得多。
            val ok = runCatching {
                Files.createSymbolicLink(alias.toPath(), File(guestPath).toPath())
                true
            }.getOrDefault(false) || runCatching {
                file.copyTo(alias, overwrite = true)
                true
            }.getOrDefault(false)
            if (ok) created += soname to name
        }
        return created
    }


    /**
     * 主程序声明依赖、但在给定目录里都找不到的库。
     *
     * 用于启动前给一条人话提示（「该版本还需要容器内系统库 libcurl.so.4」），
     * 而不是让用户对着 box64 的 `Error loading needed libs` 自己猜。
     * 只统计 `.so` 形式的依赖：`ld-linux-x86-64.so.2` 这类一定在 box64 侧解析。
     */
    fun missingNeeded(entry: File, searchDirs: List<File>): List<String> {
        val needed = read(entry)?.needed.orEmpty()
        if (needed.isEmpty()) return emptyList()
        val available = HashSet<String>()
        searchDirs.forEach { dir ->
            dir.listFiles()?.forEach { f -> if (f.isFile) available += f.name }
        }
        return needed.filter { dep ->
            if (!dep.contains(".so")) return@filter false
            // 动态加载器由 box64 自己提供（内置 x86libs 里那份），不算「缺库」
            if (dep.startsWith("ld-") || dep.startsWith("ld-linux")) return@filter false
            if (dep in available) return@filter false
            // 带版本号的依赖也可能由同名不同尾的文件提供（libssl.so.1.1 ← libssl.so）
            val stem = dep.substringBefore(".so")
            available.none { it.startsWith(stem) && it.contains(".so") }
        }.distinct()
    }

    private fun isSharedLib(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".so") || lower.contains(".so.")
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLong(b: ByteArray, off: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return value
    }
}
