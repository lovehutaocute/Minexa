package com.mineserve.mobile.data.skin

import androidx.compose.ui.graphics.Color
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 主题包安装链路的验收用例。
 *
 * 锁的都是「放过去就会变成线上事故」的那几条：
 * - zip slip / 可执行内容 / 非法颜色 / 缺 background / 校验和不符 —— 必须拒绝；
 * - 正常包 —— 必须装成功，且解析出来的配色与包里写的一致；
 * - 内置包 —— 必须真的能装上（`assets/themes/` 下的 `.mctheme` 就是这个功能的默认内容）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinInstallerTest {

    private val context = org.robolectric.RuntimeEnvironment.getApplication()
    private val installer = SkinInstaller(context)

    private fun internalPack(name: String): File =
        File("src/main/assets/themes/$name").also { require(it.isFile) { "缺少内置包 $name" } }

    /** 取内置包里的真实背景图，用来让「其它方面都合法」的测试包能通过图片预检。 */
    private fun realBackground(): ByteArray = ZipFile(internalPack("mist-neutral.mctheme")).use { zip ->
        zip.getInputStream(zip.getEntry("assets/background.webp")).readBytes()
    }

    private fun install(name: String) = installer.installStream(internalPack(name).inputStream())

    @Test
    fun `内置最小包可安装并可解析出配色`() {
        val result = install("mist-neutral.mctheme")
        assertTrue("安装失败：$result", result is SkinInstaller.Result.Ok)
        val runtime = SkinRuntime.load(File(installer.themesDir(), "mist-neutral"), isBuiltin = true)
        assertNotNull(runtime)
        val resolved = runtime!!.resolve(dark = false, targetWidthPx = 1080)
        assertEquals("mist-neutral", resolved.id)
        // tokens.json 里 color.light.primary = #3F5A8A
        assertEquals(Color(0xFF3F5A8A), resolved.scheme.primary)
    }

    @Test
    fun `内置蝶翼主题能装上且 14 个槽位全部就位`() {
        val result = install("firefly-pearl.mctheme")
        assertTrue("安装失败：$result", result is SkinInstaller.Result.Ok)
        val runtime = SkinRuntime.load(File(installer.themesDir(), "firefly-pearl"), true)!!
        val light = runtime.resolve(dark = false, targetWidthPx = 1080)
        val dark = runtime.resolve(dark = true, targetWidthPx = 1080)
        assertTrue(light.scheme.background != dark.scheme.background)
        assertNotNull("整屏背景必须解码出来", light.assets.background)

        // 这套主题不给 hero/accent 插画，Hero 卡整块用贴图（heroCard），所以那两个必须为空
        assertNull(light.assets.hero)
        assertNull(light.assets.accent)
        assertEquals(false, light.assets.heroOverflow)
        assertEquals(false, light.assets.accentOverflow)

        // 14 个组件槽位一个不少
        SkinTileSlots.ALL.forEach { slot ->
            assertNotNull("槽位 $slot 没有贴图", light.assets.tiles[slot])
        }

        // ── 落点令牌：锁的是「主题包写的值真的被界面读到了」 ──
        assertEquals(0f, light.metrics.heroScrim, 0.001f)
        assertEquals(Color(0xFF253671), light.ext.pillStart)
        assertEquals(Color(0xFF3B4C9C), light.ext.pillEnd)
        assertEquals(Color(0xFF6E86B8), light.ext.heroTrack)
        assertEquals(Color(0xFF1DB396), light.ext.heroBarStart)
        assertEquals(Color(0xFF7FE3C6), light.ext.heroBarEnd)
        assertEquals(Color(0xFFFCFDFB), light.ext.glassTop)
        assertEquals(Color(0xFFDCE4EF), light.ext.cardRim)
        assertEquals(Color(0xFFCFF2E5), light.ext.navActiveStart)
        // 金线描边只做在「显式写了 gold」的包上
        assertTrue(light.ext.hasExplicitGold)
        assertEquals(Color(0xFFC9A65A), light.ext.gold)
        // 深色压罩用主题自己的夜色，而不是纯黑
        assertEquals(Color(0xFF080F1E), dark.ext.deep)
    }

    @Test
    fun `导出与导入字节一致`() {
        install("mist-neutral.mctheme")
        val out = File(context.cacheDir, "exported.mctheme")
        out.outputStream().use { installer.export("mist-neutral", it) }
        assertEquals(
            internalPack("mist-neutral.mctheme").readBytes().size,
            out.readBytes().size
        )
    }

    @Test
    fun `zip slip 条目被拒绝`() {
        val bad = zipOf(
            "evil.mctheme",
            mapOf(
                "manifest.json" to MANIFEST.toByteArray(),
                "tokens.json" to TOKENS.toByteArray(),
                "../outside.txt" to "x".toByteArray()
            )
        )
        assertRejected(bad)
        assertTrue(!File(context.filesDir, "outside.txt").exists())
    }

    @Test
    fun `可执行内容被拒绝`() {
        val bad = zipOf(
            "so.mctheme",
            mapOf(
                "manifest.json" to MANIFEST.toByteArray(),
                "tokens.json" to TOKENS.toByteArray(),
                "assets/payload.so" to "x".toByteArray()
            )
        )
        assertRejected(bad)
    }

    @Test
    fun `非法颜色被拒绝并指出键名`() {
        val badTokens = TOKENS.replace("\"primary\":\"#3F5A8A\"", "\"primary\":\"#3F5A8Z\"")
        val bad = zipOf(
            "color.mctheme",
            mapOf(
                "manifest.json" to MANIFEST.toByteArray(),
                "tokens.json" to badTokens.toByteArray(),
                "assets/bg.webp" to realBackground()
            )
        )
        val failed = assertRejected(bad)
        assertTrue(failed.reason, failed.reason.contains("primary"))
    }

    @Test
    fun `缺 background 被拒绝`() {
        val noBg = MANIFEST.replace("{\"background\":\"assets/bg.webp\"}", "{}")
        val bad = zipOf(
            "nobg.mctheme",
            mapOf("manifest.json" to noBg.toByteArray(), "tokens.json" to TOKENS.toByteArray())
        )
        assertRejected(bad)
    }

    @Test
    fun `校验和不符被拒绝`() {
        val bad = zipOf(
            "ck.mctheme",
            mapOf(
                "manifest.json" to MANIFEST.toByteArray(),
                "tokens.json" to TOKENS.toByteArray(),
                "assets/bg.webp" to realBackground(),
                "checksums.json" to
                    """{"algorithm":"sha256","files":{"tokens.json":"sha256:deadbeef"}}""".toByteArray()
            )
        )
        val failed = assertRejected(bad)
        assertTrue(failed.reason, failed.reason.contains("校验和"))
    }

    @Test
    fun `id 非法被拒绝`() {
        val bad = zipOf(
            "id.mctheme",
            mapOf(
                "manifest.json" to MANIFEST.replace("\"demo\"", "\"Demo Theme\"").toByteArray(),
                "tokens.json" to TOKENS.toByteArray(),
                "assets/bg.webp" to realBackground()
            )
        )
        assertRejected(bad)
    }

    @Test
    fun `hex 颜色解析拒绝非法写法`() {
        assertNull(parseHexColor("#RGB"))
        assertNull(parseHexColor("#3A55A"))
        assertNull(parseHexColor("rgb(1,2,3)"))
        assertNotNull(parseHexColor("#3A55A8"))
        // 8 位色值超过 Int.MAX_VALUE，必须走 Long 截断而不是崩溃
        assertNotNull(parseHexColor("#F53A55A8"))
    }

    @Test
    fun `对比度计算会对半透明前景做合成`() {
        val white = parseRgba("#FFFFFF")!!
        val black = parseRgba("#000000")!!
        assertEquals(21.0, contrastRatio(white, black), 0.01)
        // 50% 透明的前景在白底上，对比度必须远小于纯黑
        val half = parseRgba("#80000000")!!
        assertTrue(contrastRatio(half, white) < 4.5)
    }

    private fun assertRejected(pack: File): SkinInstaller.Result.Failed {
        val result = installer.installStream(pack.inputStream())
        assertTrue("应当拒绝，实际：$result", result is SkinInstaller.Result.Failed)
        return result as SkinInstaller.Result.Failed
    }

    private fun zipOf(name: String, entries: Map<String, ByteArray>): File {
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (entryName, body) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(body)
                zos.closeEntry()
            }
        }
        return file
    }

    private companion object {
        const val MANIFEST = """
        {
          "schemaVersion": 1,
          "id": "demo",
          "name": "Demo",
          "version": "1.0.0",
          "author": "t",
          "description": "d",
          "minAppVersionCode": 1,
          "supportsDark": true,
          "assets": {"background":"assets/bg.webp"}
        }
        """

        private val ROLES = listOf(
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
            "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
            "background", "onBackground", "surface", "onSurface",
            "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant", "surfaceTint",
            "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
            "surfaceContainerHigh", "surfaceContainerHighest", "surfaceDim", "surfaceBright",
            "inverseSurface", "inverseOnSurface", "inversePrimary", "scrim"
        )

        private val EXT = listOf(
            "glassTop", "glassMid", "glassBottom", "glassRim", "cardRim",
            "pillStart", "pillEnd", "navActiveStart", "navActiveEnd",
            "heroTrack", "heroBarStart", "heroBarEnd", "heroOnDim", "heroOnSoft",
            "inkTitle", "inkBody", "inkHint", "night", "deep", "hydro",
            "gold", "goldInk", "topGlow", "navSurface"
        )

        /** 32 角色色 + 24 扩展色各两套，全部用同一个合法色值，只为通过结构校验。 */
        val TOKENS: String = buildString {
            append("{\"schemaVersion\":1,\"shape\":{},\"metrics\":{},")
            append("\"color\":{")
            append("\"light\":{" + ROLES.joinToString(",") { "\"$it\":\"#3F5A8A\"" } + "},")
            append("\"dark\":{" + ROLES.joinToString(",") { "\"$it\":\"#3F5A8A\"" } + "}},")
            append("\"ext\":{")
            append("\"light\":{" + EXT.joinToString(",") { "\"$it\":\"#3F5A8A\"" } + "},")
            append("\"dark\":{" + EXT.joinToString(",") { "\"$it\":\"#3F5A8A\"" } + "}},")
            append("\"typography\":{}}")
        }
    }
}
