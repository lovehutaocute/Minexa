package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * installer 依赖清单解析：必须覆盖 install_profile.json ∪ version json。
 *
 * 锁的是 2026-11-21 审查实测出的真实缺口：NeoForge 21.1.80 只按 profile 预下载时
 * 会漏掉 `cpw.mods:bootstraplauncher:2.0.2`，installer 直接 `These libraries failed
 * to download` → exit 1；20.6.119 漏 11 个。这里用合成的 jar 把「两份清单都要读、
 * 三种 `json` 字段形态都要认」固定下来，免得以后改回去。
 */
class InstallerLibraryManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun lib(path: String, url: String, sha1: String? = "deadbeef") = """
        {
          "name": "${path.replace('/', ':')}",
          "downloads": { "artifact": { "path": "$path", "url": "$url", "sha1": "$sha1" } }
        }
    """.trimIndent()

    private fun jarWith(entries: Map<String, String>): File {
        val file = File(temp.root, "installer.jar")
        JarOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `merges profile and referenced version json`() {
        val profile = """
            {
              "json": "/version.json",
              "libraries": [ ${lib("a/shared/1.0/shared-1.0.jar", "https://maven.neoforged.net/releases/a/shared/1.0/shared-1.0.jar")} ]
            }
        """.trimIndent()
        val version = """
            {
              "libraries": [
                ${lib("a/shared/1.0/shared-1.0.jar", "https://maven.neoforged.net/releases/a/shared/1.0/shared-1.0.jar")},
                ${lib("cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar", "https://maven.neoforged.net/releases/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar")}
              ]
            }
        """.trimIndent()
        val jar = jarWith(mapOf("install_profile.json" to profile, "version.json" to version))

        val libs = InstallerLibraryManifest.libraries(jar)

        assertEquals(2, libs.size)
        assertTrue(libs.any { it.path.contains("bootstraplauncher") })
    }

    @Test
    fun `accepts inline json object instead of file name`() {
        val profile = """
            {
              "json": {
                "libraries": [
                  ${lib("inline/only/1.0/only-1.0.jar", "https://libraries.minecraft.net/inline/only/1.0/only-1.0.jar")}
                ]
              },
              "libraries": [
                ${lib("profile/only/1.0/only-1.0.jar", "https://maven.neoforged.net/releases/profile/only/1.0/only-1.0.jar")}
              ]
            }
        """.trimIndent()
        val jar = jarWith(mapOf("install_profile.json" to profile))

        val libs = InstallerLibraryManifest.libraries(jar)

        assertEquals(2, libs.size)
        assertTrue(libs.any { it.path.startsWith("inline/") })
        assertTrue(libs.any { it.path.startsWith("profile/") })
    }

    @Test
    fun `falls back to version json when profile has no json field`() {
        val profile = """
            { "libraries": [ ${lib("p/1/p-1.jar", "https://maven.neoforged.net/releases/p/1/p-1.jar")} ] }
        """.trimIndent()
        val version = """
            { "libraries": [ ${lib("v/1/v-1.jar", "https://libraries.minecraft.net/v/1/v-1.jar")} ] }
        """.trimIndent()
        val jar = jarWith(mapOf("install_profile.json" to profile, "version.json" to version))

        assertEquals(2, InstallerLibraryManifest.libraries(jar).size)
    }

    @Test
    fun `drops entries without direct url and dedupes by path`() {
        val profile = """
            {
              "libraries": [
                { "name": "no:direct:url:1" },
                ${lib("dup/1/dup-1.jar", "https://maven.neoforged.net/releases/dup/1/dup-1.jar")},
                ${lib("dup/1/dup-1.jar", "https://maven.neoforged.net/releases/dup/1/dup-1.jar")}
              ]
            }
        """.trimIndent()
        val jar = jarWith(mapOf("install_profile.json" to profile))

        val libs = InstallerLibraryManifest.libraries(jar)

        assertEquals(1, libs.size)
        assertEquals("dup/1/dup-1.jar", libs.single().path)
    }

    @Test
    fun `returns empty list when jar has no install profile`() {
        val jar = jarWith(mapOf("readme.txt" to "no profile here"))

        assertTrue(InstallerLibraryManifest.libraries(jar).isEmpty())
    }

    @Test
    fun `falls back to version json when referenced entry is missing`() {
        // profile 的 json 字段指向一个 jar 里并不存在的文件名：不能因此丢掉整份清单
        val profile = """
            {
              "json": "/not-there.json",
              "libraries": [ ${lib("p/1/p-1.jar", "https://maven.neoforged.net/releases/p/1/p-1.jar")} ]
            }
        """.trimIndent()
        val version = """
            { "libraries": [ ${lib("v/1/v-1.jar", "https://libraries.minecraft.net/v/1/v-1.jar")} ] }
        """.trimIndent()
        val jar = jarWith(mapOf("install_profile.json" to profile, "version.json" to version))

        val libs = InstallerLibraryManifest.libraries(jar)

        assertEquals(2, libs.size)
        assertTrue(libs.any { it.path.startsWith("v/") })
    }

    @Test
    fun `rejects path traversal and absolute paths`() {
        val root = temp.newFolder("libraries")

        assertEquals(
            File(root, "a/b/c.jar").canonicalPath,
            InstallerLibraryManifest.resolveTarget(root, "a/b/c.jar")?.path
        )
        // 相对穿越
        assertEquals(null, InstallerLibraryManifest.resolveTarget(root, "../../pwned.jar"))
        // 带子目录的穿越（规范化后逃出 root）
        assertEquals(null, InstallerLibraryManifest.resolveTarget(root, "a/../../../pwned.jar"))
        // 绝对路径：File(root, abs) 在 Java 里就是 abs，必须挡掉
        assertEquals(null, InstallerLibraryManifest.resolveTarget(root, "/tmp/pwned.jar"))
    }
}
