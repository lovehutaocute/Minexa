package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `libipv6fix.so` 的产物守卫：**导出的符号**必须还在。
 *
 * ## 为什么需要这个测试
 *
 * 这个库靠 `LD_PRELOAD` 抢占 JVM 里的 `fopen` / `fopen64` 符号来生效。而它的编译
 * 命令一旦带上 `-fvisibility=hidden`（很自然的一步「优化」，heaptagfix.c 的编译
 * 命令里就有），这两个符号会被**藏进静态符号表**：`.dynsym` 里查不到 ⇒ linker
 * 不会让本库参与符号解析 ⇒ 整层兼容失效。
 *
 * 失效方式是最坏的那种：**服务端照旧启动，只是 `server-ip=::` 又崩回
 * `UnsupportedAddressTypeException`** —— 从日志、从 UI、从退出码都看不出
 * 「补丁没生效」，只能从 JDK 源码反推一次。所以这里直接从 ELF 的 `.dynsym`
 * 里核对它俩是 GLOBAL 且已定义（`st_shndx != SHN_UNDEF`，即导出而非引用）。
 *
 * 纯静态事实、不需要设备，与 `BundledLibrariesTest` 同一套路。
 */
class Ipv6FixLibraryTest {

    private fun jniLibsDir(): File {
        val candidates = listOf(
            File("src/main/jniLibs/arm64-v8a"),
            File("app/src/main/jniLibs/arm64-v8a")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 jniLibs/arm64-v8a：${candidates.joinToString { it.absolutePath }}")
    }

    private fun library(): File = File(jniLibsDir(), NativeLibraryBundler.IPV6_FIX_LIB)

    @Test
    fun exportsFopenAndFopen64() {
        val lib = library()
        assertTrue("${lib.name} 不在 jniLibs 里", lib.isFile)
        val symbols = dynamicSymbols(lib)
        listOf("fopen", "fopen64").forEach { name ->
            val entry = symbols[name]
            assertTrue(
                "$name 不在 ${lib.name} 的 .dynsym 里（LD_PRELOAD 会静默失效：" +
                    "编译时不能加 -fvisibility=hidden）",
                entry != null
            )
            assertTrue("$name 未导出，只是被引用（st_shndx=UND）", entry!!.defined)
            assertEquals("$name 不是 GLOBAL 绑定，linker 不会用它覆盖 libc 的实现", 1, entry.binding)
        }
    }

    /**
     * 只允许依赖进程启动时**已经加载**的系统库。
     *
     * 本库要被塞进 java 进程（LD_PRELOAD），任何预期外的 NEEDED 都可能让这次加载
     * 失败 —— 而失败是静默的（linker 报一行 stderr 就过去了），服务端照常启动，
     * 只是 IPv6 又没了。
     */
    @Test
    fun onlyDependsOnAlreadyLoadedSystemLibraries() {
        val needed = NativeLibraryBundler.neededLibrariesOf(library())
        val unexpected = needed.filter { it != "libc.so" && it != "libdl.so" }
        assertTrue(
            "${NativeLibraryBundler.IPV6_FIX_LIB} 引入了预期外的依赖 $unexpected " +
                "（NEEDED 只允许 libc.so / libdl.so）",
            unexpected.isEmpty()
        )
    }

    // ──────────────────────────────────────────────────────────
    // 极简 ELF64 动态符号表读取（只做本测试需要的事）
    // ──────────────────────────────────────────────────────────

    private data class Symbol(val name: String, val binding: Int, val defined: Boolean)

    /** C 字符串在字节流里的结束位置（Kotlin 的 ByteArray 没有带起点的 indexOf）。 */
    private fun nulEnd(b: ByteArray, from: Int): Int {
        var i = from
        while (i < b.size && b[i] != 0.toByte()) i++
        return i
    }

    private fun dynamicSymbols(file: File): Map<String, Symbol> {
        val b = file.readBytes()
        fun u8(off: Int) = b[off].toInt() and 0xff
        fun u16(off: Int) = u8(off) or (u8(off + 1) shl 8)
        fun u32(off: Int) = u16(off).toLong() or (u16(off + 2).toLong() shl 16)
        fun u64(off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or u8(off + i).toLong()
            return v
        }

        assertEquals("不是 64 位 ELF", 2, u8(4))
        val shOff = u64(0x28)
        val shEntSize = u16(0x3A)
        val shNum = u16(0x3C)
        val shStrNdx = u16(0x3E)

        // 节头：sh_name(0) sh_type(4) sh_flags(8) sh_addr(16) sh_offset(24) sh_size(32)
        //       sh_link(40) sh_info(44) sh_addralign(48) sh_entsize(56)
        fun sh(i: Int): Long = shOff + i.toLong() * shEntSize
        fun nameOf(i: Int): String {
            val strTab = sh(shStrNdx)
            val base = u64(strTab.toInt() + 24)   // .shstrtab 的 sh_offset
            val off = (base + u32(sh(i).toInt())).toInt()
            val end = nulEnd(b, off)
            return String(b, off, end - off, Charsets.US_ASCII)
        }

        var dynsym = -1L
        var dynstrIndex = -1
        for (i in 0 until shNum) {
            when (nameOf(i)) {
                ".dynsym" -> dynsym = sh(i)
                ".dynstr" -> dynstrIndex = i
            }
        }
        assertTrue(".dynsym 不存在", dynsym >= 0)
        assertTrue(".dynstr 不存在", dynstrIndex >= 0)

        val symOff = u64(dynsym.toInt() + 24).toInt()
        val symSize = u64(dynsym.toInt() + 32).toLong()
        val symEnt = u64(dynsym.toInt() + 56).toInt()
        val strOff = u64(sh(dynstrIndex).toInt() + 24).toInt()

        val out = LinkedHashMap<String, Symbol>()
        var p = symOff
        while (p < symOff + symSize) {
            val stName = u32(p).toInt()
            val stInfo = u8(p + 4)
            val stShndx = u16(p + 6)
            val nameStart = strOff + stName
            val nameEnd = nulEnd(b, nameStart)
            val name = String(b, nameStart, nameEnd - nameStart, Charsets.US_ASCII)
            if (name.isNotEmpty()) {
                out[name] = Symbol(name, stInfo shr 4, stShndx != 0)
            }
            p += if (symEnt > 0) symEnt else 24
        }
        return out
    }
}
