package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「打包清单」与「jniLibs 实际文件」的一致性守卫。
 *
 * ## 为什么需要它（真实事故）
 * [NativeLibraryBundler.PACKAGED_LIBRARIES] 早就列出了 `libandroid-glob.so` 等 21 个库，
 * 但 `app/src/main/jniLibs/` 目录**根本不存在** —— 清单有、文件没有，
 * [NativeLibraryBundler.provision] 因此一直在空转，APK 里一个补充库都没有。
 *
 * 后果（荣耀 Magic 6 实测）：设备上 apt-get 在**动态链接阶段**就退出
 *
 * ```
 * F linker: CANNOT LINK EXECUTABLE ".../bin/apt-get":
 *           library "libandroid-glob.so" not found: needed by main executable
 * ```
 *
 * 现象正是「一键安装依赖库」等待数秒后报错、无任何日志输出、也没有网络连接。
 *
 * ## 这个测试能提前拦住它
 * 清单与文件都在仓库里，属于**纯静态事实**，不需要设备也不需要 NDK 就能校验：
 * 任何一次「加了清单项却忘了放文件」（或反过来）都会在 CI 直接失败。
 */
class BundledLibrariesTest {

    /**
     * 不在 [NativeLibraryBundler.PACKAGED_LIBRARIES] 里、但确实该躺在 jniLibs 里的文件：
     * `heaptagfix.c` 与 `ipv6fix.c` 的编译产物
     * （见 [NativeLibraryBundler.HEAP_TAG_FIX_LIB] / [NativeLibraryBundler.IPV6_FIX_LIB]）。
     * 它们不经 provision 落盘（TermuxRuntime 直接以绝对路径交给 LD_PRELOAD），
     * 所以不进打包清单。
     */
    private val extraJniLibs = setOf(
        NativeLibraryBundler.HEAP_TAG_FIX_LIB,
        NativeLibraryBundler.IPV6_FIX_LIB
    )

    /** 单元测试的工作目录是模块目录（app/），但兼容从仓库根目录运行的情况。 */
    private fun jniLibsDir(): File {
        val candidates = listOf(
            File("src/main/jniLibs/arm64-v8a"),
            File("app/src/main/jniLibs/arm64-v8a")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 jniLibs/arm64-v8a：${candidates.joinToString { it.absolutePath }}")
    }

    @Test
    fun everyBundledLibraryIsActuallyPackaged() {
        val dir = jniLibsDir()
        val missing = NativeLibraryBundler.PACKAGED_LIBRARIES.keys
            .filter { !File(dir, it).isFile }
        assertEquals(
            "清单声明要打包、jniLibs 里却没有的库（设备上会因缺库崩溃）",
            emptySet<String>(),
            missing.toSet()
        )
    }

    @Test
    fun noUnlistedLibrarySitsInJniLibs() {
        val dir = jniLibsDir()
        val files = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val unlisted = files - NativeLibraryBundler.PACKAGED_LIBRARIES.keys - extraJniLibs
        assertEquals(
            "jniLibs 里既没登记进 PACKAGED_LIBRARIES、也不属于 extraJniLibs 的文件：" +
                "它们会被打进 APK 却没有任何代码会用到",
            emptySet<String>(),
            unlisted
        )
    }

    /**
     * 落盘名必须等于库自己的 SONAME。
     *
     * linker 查找依赖只用**硬名字**，不做前缀匹配：`libtalloc.so.2` 找不到时，
     * 磁盘上摆着 `libtalloc.so.2.4.3` 也没用。所以「打包名 → 运行时名」这层映射
     * 一旦写错，等于这个库根本没打包，而且只会在设备上以
     * `CANNOT LINK EXECUTABLE ... library "x" not found` 的形式暴露。
     *
     * 这条守卫在 2026-09-27 真机事故（proot 起不来 → Ubuntu 容器 → Java 8 全线失败）
     * 之后补上：当时的问题正是「库没打包」，不是映射错，但映射错会以同样的现象出现，
     * 而那时已经没有任何东西能把它和「没打包」区分开。
     *
     * 没有 DT_SONAME 的库（`libandroid-shmem.so` / `libandroid-glob.so` 等）跳过 ——
     * linker 就是按文件名找它们，打包名 = 运行时名即可。
     */
    @Test
    fun runtimeNameMatchesLibrarySoname() {
        val dir = jniLibsDir()
        val mismatched = NativeLibraryBundler.PACKAGED_LIBRARIES.mapNotNull { (packaged, runtime) ->
            val file = File(dir, packaged)
            if (!file.isFile) return@mapNotNull null
            val soname = NativeLibraryBundler.sonameOf(file) ?: return@mapNotNull null
            if (soname == runtime) null else "$packaged：SONAME 是 $soname，清单却映射成 $runtime"
        }
        assertEquals(
            "清单里的「运行时名」与库自己的 SONAME 不一致（设备上会报 library not found）",
            emptyList<String>(),
            mismatched
        )
    }

    /**
     * 预加载库的产物必须存在（heaptagfix / ipv6fix）。
     *
     * 它们是仅有的两个**不经过常规构建**（分别由 build-heaptagfix、build-ipv6fix
     * 工作流生成）的文件：一旦漏掉，TermuxRuntime 的 LD_PRELOAD 会静默退化成空串
     * —— 不报错、没日志，只是「关闭堆指针标签」与「IPv6 判定」这两项补丁又变回死的。
     */
    @Test
    fun preloadLibrariesArePackaged() {
        val dir = jniLibsDir()
        extraJniLibs.forEach { name ->
            val f = File(dir, name)
            assertTrue(
                "$name 不在 jniLibs 里（改过对应 .c 后要跑一次那个 build-*.yml 工作流）",
                f.isFile
            )
            assertTrue("$name 是空文件", f.length() > 0L)
        }
    }

    @Test
    fun packagedLibrariesAreNonEmptyArm64Elf() {
        val dir = jniLibsDir()
        (NativeLibraryBundler.PACKAGED_LIBRARIES.keys + extraJniLibs)
            .map { File(dir, it) }
            .filter { it.isFile }
            .forEach { f ->
                assertTrue("${f.name} 是空文件", f.length() > 0L)

                val head = ByteArray(20)
                val read = f.inputStream().use { it.read(head) }
                assertTrue("${f.name} 读取头失败", read == head.size)
                assertTrue(
                    "${f.name} 不是 ELF 文件",
                    head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                        head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
                )
                assertEquals("${f.name} 不是 64 位 ELF", 2, head[4].toInt())

                // e_machine：0x00B7 = EM_AARCH64。防的是误把 x86_64/arm 的库放进 arm64-v8a。
                val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
                assertEquals("${f.name} 不是 arm64 架构（e_machine=$machine）", 0xB7, machine)
            }
    }
}
