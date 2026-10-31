package com.mineserve.mobile.server

import com.mineserve.mobile.data.ServerCore
import java.io.File
import java.util.jar.JarFile

/**
 * 服务器核心与版本自动识别（启发式，不联网）：
 *
 * 依据服务器目录中的特征文件 / 目录结构 / 核心 jar 内容判断：
 *  - 核心类型：PowerNukkitX / Quilt / Fabric / Velocity / BungeeCord / Forge / NeoForge / Leaves / Leaf / Purpur / Paper / Spigot / CraftBukkit / Vanilla
 *  - MC 版本：优先根目录 version.json（Vanilla/Paper 系首次启动生成），
 *    其次核心 jar 内嵌 version.json（Paper/Purpur），最后按目录布局推算（Forge/NeoForge）。
 *
 * 识别失败时 core / version 返回 null，由调用方兜底展示，不影响导入流程。
 */
object ServerCoreDetector {

    data class Detection(
        val core: ServerCore?,
        val version: String?,
        /** 根目录中实际可启动的核心 JAR；不会被改名为 server.jar。 */
        val serverFile: String? = null
    )

    fun detect(serverDir: File): Detection {
        if (!serverDir.isDirectory) return Detection(null, null)
        val rootVersion = readVersionJson(File(serverDir, "version.json"))
        val rootJars = serverDir.listFiles { f ->
            f.isFile && f.name.endsWith(".jar", ignoreCase = true)
        }?.toList() ?: emptyList()

        // ── 0. 官方基岩版 BDS（原生可执行文件，无 jar） ──────────────
        // 必须放在最前：BDS 目录里也可能有用户自己塞的 jar（插件/工具），
        // 但只要有 bedrock_server 就确定是 BDS 布局。
        if (BdsSupport.isBdsDir(serverDir)) {
            return Detection(ServerCore.Bds, bdsVersion(serverDir), BdsSupport.ENTRY_NAME)
        }

        // ── 1. PowerNukkitX（基岩版） ────────────────────────────────
        if (rootJars.any { it.name.equals("powernukkitx.jar", ignoreCase = true) } ||
            hasClassEntry(rootJars, "cn/nukkit/Server.class") ||
            hasClassEntry(rootJars, "org/powernukkitx/Server.class") ||
            hasClassEntry(rootJars, "org/powernukkitx/JarStart.class")
        ) {
            val gameVersion = mojangCacheVersion(serverDir)
            val coreVersion = jarManifestValue(
                rootJars.firstOrNull { it.name.equals("powernukkitx.jar", ignoreCase = true) },
                "Implementation-Version"
            )
            return Detection(ServerCore.PowerNukkitX, gameVersion ?: coreVersion ?: rootVersion, entryJar(rootJars, "powernukkitx.jar")?.name)
        }

        // ── 2. Quilt ────────────────────────────────────────────────
        if (rootJars.any { it.name.equals("quilt-server-launch.jar", ignoreCase = true) }) {
            return Detection(ServerCore.Quilt, rootVersion ?: jarVersionJson(rootJars), entryJar(rootJars, "quilt-server-launch.jar")?.name)
        }

        // ── 3. Fabric ───────────────────────────────────────────────
        if (rootJars.any { it.name.equals("fabric-server-launch.jar", ignoreCase = true) } ||
            File(serverDir, ".fabric").isDirectory
        ) {
            return Detection(ServerCore.Fabric, rootVersion ?: jarVersionJson(rootJars), entryJar(rootJars, "fabric-server-launch.jar")?.name)
        }

        // ── 4. Velocity（代理端，版本从 jar 文件名读取） ──────────────
        rootJars.firstOrNull { it.name.startsWith("velocity-", ignoreCase = true) }?.let { jar ->
            val v = jar.name.removePrefix("velocity-").removeSuffix(".jar").ifBlank { null }
            return Detection(ServerCore.Velocity, v, jar.name)
        }

        // ── 5. BungeeCord（代理端） ─────────────────────────────────
        rootJars.firstOrNull {
            it.name.equals("BungeeCord.jar", ignoreCase = true) ||
                it.name.equals("bungee.jar", ignoreCase = true)
        }?.let { jar ->
            return Detection(ServerCore.BungeeCord, jarManifestValue(jar, "Implementation-Version"), jar.name)
        }
        if (hasManifestMain(rootJars, "net.md_5.bungee.BungeeCord")) {
            return Detection(ServerCore.BungeeCord, rootJars.firstNotNullOfOrNull {
                jarManifestValue(it, "Implementation-Version")
            }, rootJars.firstOrNull { hasManifestMain(listOf(it), "net.md_5.bungee.BungeeCord") }?.name)
        }

        // ── 6. Forge（libraries/net/minecraftforge/forge/<mc>-<fv>/） ──
        val forgeRoot = File(serverDir, "libraries/net/minecraftforge/forge")
        if (forgeRoot.isDirectory) {
            val firstDir = forgeRoot.listFiles { f -> f.isDirectory }?.firstOrNull()
            val mcVersion = firstDir?.name?.substringBefore("-")?.takeIf { it.isNotEmpty() }
            return Detection(ServerCore.Forge, mcVersion ?: rootVersion, entryJar(rootJars)?.name)
        }

        // ── 7. NeoForge（libraries/net/neoforged/neoforge/<nv>/） ────
        val neoRoot = File(serverDir, "libraries/net/neoforged/neoforge")
        if (neoRoot.isDirectory) {
            val nv = neoRoot.listFiles { f -> f.isDirectory }?.firstOrNull()?.name
            val mcVersion = nv?.let { neoForgeMcVersion(it) }
            return Detection(ServerCore.NeoForge, mcVersion ?: rootVersion, entryJar(rootJars)?.name)
        }

        // ── 8. Paper 分支（必须先于 Paper，避免被 Paper 共用类误判） ──
        if (hasClassPrefix(rootJars, "org/leavesmc/leaves/") ||
            hasClassPrefix(rootJars, "org/leavesmc/leaf/")
        ) {
            val jar = rootJars.firstOrNull { hasClassPrefix(listOf(it), "org/leavesmc/leaves/") || hasClassPrefix(listOf(it), "org/leavesmc/leaf/") }
            val core = if (hasClassPrefix(rootJars, "org/leavesmc/leaves/")) ServerCore.Leaves else ServerCore.Leaf
            return Detection(core, rootVersion ?: jarVersionJson(rootJars), jar?.name ?: entryJar(rootJars)?.name)
        }

        if (hasClassPrefix(rootJars, "org/purpurmc/")) {
            val jar = rootJars.firstOrNull { hasClassPrefix(listOf(it), "org/purpurmc/") }
            return Detection(ServerCore.Purpur, rootVersion ?: jarVersionJson(rootJars), jar?.name)
        }

        // ── 9. Paper（核心 jar 内嵌 io/papermc/paper/ 或 paperclip 启动类） ──
        if (hasClassPrefix(rootJars, "io/papermc/paper/") ||
            hasManifestMain(rootJars, "io.papermc.paperclip.Main")
        ) {
            val jar = rootJars.firstOrNull {
                hasClassPrefix(listOf(it), "io/papermc/paper/") ||
                    hasManifestMain(listOf(it), "io.papermc.paperclip.Main")
            }
            return Detection(ServerCore.Paper, rootVersion ?: jarVersionJson(rootJars), jar?.name)
        }

        // ── 10. Spigot / CraftBukkit（GetBukkit 核心） ───────────────
        if (hasClassEntry(rootJars, "org/bukkit/Bukkit.class") ||
            hasClassPrefix(rootJars, "org/bukkit/craftbukkit/")
        ) {
            val bukkitJar = rootJars.firstOrNull {
                hasClassEntry(listOf(it), "org/bukkit/Bukkit.class") ||
                    hasClassPrefix(listOf(it), "org/bukkit/craftbukkit/")
            }
            val name = bukkitJar?.name?.lowercase() ?: ""
            val craft = name.startsWith("craftbukkit-") ||
                (!name.startsWith("spigot-") && hasClassPrefix(rootJars, "org/bukkit/craftbukkit/"))
            return Detection(
                if (craft) ServerCore.CraftBukkit else ServerCore.Spigot,
                rootVersion ?: jarManifestValue(bukkitJar, "Implementation-Version"),
                bukkitJar?.name
            )
        }

        // ── 11. Vanilla（server.jar 且无其它特征） ───────────────────
        if (rootJars.any { it.name.equals("server.jar", ignoreCase = true) }) {
            return Detection(ServerCore.Vanilla, rootVersion, entryJar(rootJars, "server.jar")?.name)
        }

        // ── 兜底：核心未知，仍尝试给出版本 ───────────────────────────
        return Detection(null, rootVersion ?: jarVersionJson(rootJars), entryJar(rootJars)?.name)
    }

    /**
     * BDS 版本：官方包里**没有**版本文件，因此下载时由我们写一份 `bds-version.txt`；
     * 用户自行解压的包通常没有，就返回 null（界面上显示「未知」而不是猜一个版本号）。
     */
    private fun bdsVersion(serverDir: File): String? {
        listOf("bds-version.txt", "version.txt").forEach { name ->
            val file = File(serverDir, name)
            if (!file.isFile) return@forEach
            val text = runCatching { file.readText().trim() }.getOrNull()
            if (!text.isNullOrBlank()) return text.lineSequence().first().trim().take(32)
        }
        return null
    }

    /** 只返回唯一的根目录入口 JAR，绝不把 installer/client/library 当成启动文件。 */    private fun entryJar(jars: List<File>, vararg preferredNames: String): File? {
        preferredNames.firstNotNullOfOrNull { name -> jars.firstOrNull { it.name.equals(name, true) } }?.let { return it }
        return jars.filter { jar ->
            val name = jar.name.lowercase()
            !name.contains("installer") && !name.contains("client") && !name.contains("library")
        }.singleOrNull()
    }

    /** NeoForge 版本号 → MC 版本：20.4.x → 1.20.4；21.0.x → 1.21；21.1.x → 1.21.1 … */
    private fun neoForgeMcVersion(neoForgeVersion: String): String? {
        val parts = neoForgeVersion.split(".")
        return when {
            parts.size >= 2 && parts[0] == "20" -> "1.20." + parts[1]
            parts.size >= 2 && parts[0] == "21" ->
                if (parts[1] == "0") "1.21" else "1.21." + parts[1]
            // 兼容少数直接以 MC 版本命名的目录（如 1.21.1-xxx）
            parts.isNotEmpty() && parts[0].startsWith("1.") -> {
                val v = neoForgeVersion.substringBefore('-')
                val segs = v.split(".")
                if (segs.size in 2..3 && segs.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                    v
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** 读取根目录 version.json 的 id 字段（Vanilla/Paper 系首次启动生成） */
    private fun readVersionJson(file: File): String? = try {
        if (!file.isFile) null else parseId(file.readText())
    } catch (_: Exception) {
        null
    }

    /** 读取核心 jar 内嵌 version.json 的 id 字段（Paper/Purpur 等打包时写入） */
    private fun jarVersionJson(jars: List<File>): String? {
        for (jar in jars) {
            try {
                JarFile(jar).use { jf ->
                    val entry = jf.getJarEntry("version.json")
                    if (entry != null) {
                        val text = jf.getInputStream(entry).bufferedReader().use { it.readText() }
                        return parseId(text)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 从 JSON 文本中提取 "id": "..."（手工解析，避免正则转义问题） */
    private fun parseId(jsonText: String): String? {
        val quote = '"'
        val key = quote.toString() + "id" + quote
        val keyIdx = jsonText.indexOf(key)
        if (keyIdx < 0) return null
        val afterKey = jsonText.substring(keyIdx + key.length)
        val colonIdx = afterKey.indexOf(':')
        if (colonIdx < 0) return null
        val afterColon = afterKey.substring(colonIdx + 1).trim()
        if (!afterColon.startsWith(quote)) return null
        val end = afterColon.indexOf(quote, 1)
        if (end < 0) return null
        return afterColon.substring(1, end)
    }

    /** PowerNukkitX：cache/mojang_<基岩版号>.jar 提供游戏版本 */
    private fun mojangCacheVersion(serverDir: File): String? {
        val cacheDir = File(serverDir, "cache")
        if (!cacheDir.isDirectory) return null
        return cacheDir.listFiles { f -> f.isFile && f.name.startsWith("mojang_") }
            ?.mapNotNull { f ->
                val name = f.name
                val end = name.lastIndexOf(".jar")
                if (end > 7) name.substring(7, end) else null // 7 = "mojang_".length
            }
            ?.firstOrNull()
    }

    /** 读取 jar MANIFEST 指定属性 */
    private fun jarManifestValue(jar: File?, attribute: String): String? {
        if (jar == null) return null
        return try {
            JarFile(jar).use { jf -> jf.manifest?.mainAttributes?.getValue(attribute) }
        } catch (_: Exception) {
            null
        }
    }

    /** 精确匹配 jar 内某个 class 条目（如 cn/nukkit/Server.class） */
    private fun hasClassEntry(jars: List<File>, entryName: String): Boolean {
        for (jar in jars) {
            try {
                JarFile(jar).use { jf ->
                    if (jf.getJarEntry(entryName) != null) return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** 匹配 jar 内是否存在以 prefix 开头的条目（包前缀，如 io/papermc/paper/） */
    private fun hasClassPrefix(jars: List<File>, prefix: String): Boolean {
        for (jar in jars) {
            try {
                JarFile(jar).use { jf ->
                    val entries = jf.entries()
                    while (entries.hasMoreElements()) {
                        if (entries.nextElement().name.startsWith(prefix)) return true
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** 匹配 jar MANIFEST 的 Main-Class */
    private fun hasManifestMain(jars: List<File>, mainClass: String): Boolean {
        for (jar in jars) {
            try {
                JarFile(jar).use { jf ->
                    val entry = jf.getJarEntry("META-INF/MANIFEST.MF")
                    if (entry != null) {
                        val text = jf.getInputStream(entry).bufferedReader().use { it.readText() }
                        if (text.lines().any { line ->
                                line.trim().startsWith("Main-Class:") && line.contains(mainClass)
                            }) return true
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }
}

