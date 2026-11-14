package com.mineserve.mobile.data.skin

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 组件贴图（v2 `assets.<槽位>`）的解析与降级。
 *
 * 这一层的每条分支都对应一个「线上会出问题」的场景：
 * - 老包没有 `assets.card` → 必须完全没有贴图，界面回到纯色（向后兼容）；
 * - 槽位名不在白名单 → 连解码都不做（防止任意 key 塞图进内存）；
 * - `ninePatch` 换算要看 `assetsMeta.width`，缺了就当整图拉伸（否则边角会被拉糊）；
 * - `../` 越界路径必须拒绝；
 * - 两条边加起来超过图宽 → 退化整图，不能画出负宽度的 9 宫格。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinTileTest {

    private val root: File = File.createTempFile("skin-tile", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    /** 写一张纯色 PNG 当贴图。 */
    private fun png(rel: String, w: Int, h: Int) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.RED)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun pack(
        assets: Map<String, String>,
        meta: Map<String, String> = emptyMap(),
        assetsDark: Map<String, String> = emptyMap(),
        metaDark: Map<String, String> = emptyMap()
    ): SkinRuntime? {
        val a = assets.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        val m = meta.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        val ad = assetsDark.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        val md = metaDark.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        val manifest = """
            {
              "schemaVersion": 1,
              "id": "tile-test",
              "name": "贴图测试",
              "version": "1",
              "author": "test",
              "assets": { $a },
              "assetsMeta": { $m },
              "assetsDark": { $ad },
              "assetsMetaDark": { $md }
            }
        """.trimIndent()
        File(root, "manifest.json").writeText(manifest)
        File(root, "tokens.json").writeText("""{"schemaVersion":1}""")
        return SkinRuntime.load(root)
    }

    @Test
    fun `老包没有组件贴图时 tiles 为空`() {
        val rt = pack(emptyMap())!!
        val skin = rt.resolve(dark = false, targetWidthPx = 1080)
        assertTrue(skin.assets.tiles.isEmpty())
    }

    @Test
    fun `白名单内的槽位会被解码`() {
        png("assets/tiles/card.png", 60, 24)
        val rt = pack(mapOf("card" to "assets/tiles/card.png"))!!
        val tile = rt.resolve(false, 1080).assets.tiles["card"]
        assertNotNull("card 槽位应该解出贴图", tile)
        assertEquals(60, tile!!.bitmap.width)
        // 没给 ninePatch → 整图拉伸
        assertFalse(tile.isNinePatch)
    }

    @Test
    fun `未知槽位被忽略`() {
        png("assets/tiles/x.png", 40, 20)
        val rt = pack(mapOf("evilSlot" to "assets/tiles/x.png"))!!
        assertTrue(rt.resolve(false, 1080).assets.tiles.isEmpty())
    }

    @Test
    fun `越界路径被拒绝`() {
        // 放到包目录外面
        val outside = File(root.parentFile, "outside-${System.currentTimeMillis()}.png")
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        outside.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val rt = pack(mapOf("card" to "../${outside.name}"))!!
        assertTrue(rt.resolve(false, 1080).assets.tiles.isEmpty())
        outside.delete()
    }

    @Test
    fun `ninePatch 按 dp 保存，源图与目标两套尺度分开换算`() {
        // 图 3 倍：328dp → 984px；边距 18dp
        png("assets/tiles/card.png", 984, 288)
        val rt = pack(
            mapOf("card" to "assets/tiles/card.png"),
            mapOf("card" to """{"width":328,"height":96,"ninePatch":[18,18,18,18]}""")
        )!!
        val tile = rt.resolve(false, 1080).assets.tiles["card"]!!
        assertTrue(tile.isNinePatch)
        // 保存的是包里写的 dp，不是换算后的像素
        assertEquals(18f, tile.left, 0.01f)
        assertEquals(18f, tile.right, 0.01f)
        // 切图：3 倍图 → 54 源像素
        val src = tile.srcInsets()
        assertEquals(54f, src[0], 0.7f)
        assertEquals(54f, src[2], 0.7f)
        // 落画布：2.75 倍屏 → 49.5 目标像素（不是 54，也不是 18）
        val dst = tile.dstInsets(2.75f)
        assertEquals(49.5f, dst[0], 0.01f)
        assertEquals(49.5f, dst[3], 0.01f)
    }

    @Test
    fun `缺设计尺寸时退化成整图拉伸`() {
        png("assets/tiles/card.png", 300, 100)
        val rt = pack(
            mapOf("card" to "assets/tiles/card.png"),
            mapOf("card" to """{"ninePatch":[18,18,18,18]}""")
        )!!
        assertFalse(rt.resolve(false, 1080).assets.tiles["card"]!!.isNinePatch)
    }

    @Test
    fun `边距吃掉整张图时退化整图`() {
        png("assets/tiles/card.png", 40, 20)
        val rt = pack(
            mapOf("card" to "assets/tiles/card.png"),
            // 设计宽 10dp、边距 6+6 刚好等于设计宽度
            mapOf("card" to """{"width":10,"height":10,"ninePatch":[6,2,6,2]}""")
        )!!
        assertFalse("左右边距超过图宽必须退化成拉伸", rt.resolve(false, 1080).assets.tiles["card"]!!.isNinePatch)
    }

    @Test
    fun `tint 标记被读出来`() {
        png("assets/tiles/card.png", 60, 24)
        val rt = pack(
            mapOf("card" to "assets/tiles/card.png"),
            mapOf("card" to """{"width":328,"height":96,"tint":true}""")
        )!!
        assertTrue(rt.resolve(false, 1080).assets.tiles["card"]!!.tint)
    }

    @Test
    fun `背景与贴图可以同时存在`() {
        png("assets/bg.png", 80, 160)
        png("assets/tiles/card.png", 60, 24)
        val rt = pack(
            mapOf("background" to "assets/bg.png", "card" to "assets/tiles/card.png")
        )!!
        val skin = rt.resolve(false, 1080)
        assertNotNull(skin.assets.background)
        assertNotNull(skin.assets.tiles["card"])
    }

    @Test
    fun `贴图文件缺失时该槽位为 null`() {
        val rt = pack(mapOf("card" to "assets/tiles/nope.png"))!!
        assertNull(rt.resolve(false, 1080).assets.tiles["card"])
    }

    @Test
    fun `深色贴图与浅色贴图分开解析`() {
        // 贴图烘死了颜色：深色模式必须用 assetsDark，否则白面板配浅字。
        png("assets/tiles/card.png", 60, 24)
        png("assets/tiles-dark/card.png", 60, 24)
        val rt = pack(
            mapOf("card" to "assets/tiles/card.png"),
            mapOf("card" to """{"width":328,"height":96,"ninePatch":[18,18,18,18]}"""),
            mapOf("card" to "assets/tiles-dark/card.png"),
            mapOf("card" to """{"width":328,"height":96,"ninePatch":[18,18,18,18]}""")
        )!!
        val light = rt.resolve(false, 1080)
        val dark = rt.resolve(true, 1080)
        // 两套都解出来，界面按 isDark 挑
        assertNotNull(light.assets.tiles["card"])
        assertNotNull(light.assets.tilesDark["card"])
        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        // 深色那份的九宫格也按 dp 保存
        assertEquals(18f, dark.assets.tilesDark["card"]!!.left, 0.01f)
    }

    @Test
    fun `深色贴图缺失时回退浅色那一套`() {
        png("assets/tiles/card.png", 60, 24)
        val rt = pack(mapOf("card" to "assets/tiles/card.png"))!!
        val dark = rt.resolve(true, 1080)
        assertTrue(dark.assets.tilesDark.isEmpty())
        assertNotNull("没有深色贴图时要能回退浅色那张", dark.assets.tiles["card"])
    }

    @Test
    fun `白名单 14 个槽位可以一次全部生效`() {
        // 界面侧 14 个槽位都接了线（Settings/顶栏/底栏/弹窗/输入框/胶囊/提示条/按钮…），
        // 这里用「一次给齐」守住两件事：SkinTileSlots.ALL 与解码白名单没有漏项，
        // 且多个槽位不会互相覆盖（曾经担心过 map 复用写坏）。
        val assets = SkinTileSlots.ALL.associateWith { "assets/tiles/$it.png" }
        assets.forEach { (_, path) -> png(path, 40, 20) }
        val rt = pack(assets)!!
        val tiles = rt.resolve(false, 1080).assets.tiles
        assertEquals(SkinTileSlots.ALL.size, tiles.size)
        SkinTileSlots.ALL.forEach { assertNotNull("槽位 $it 没解出来", tiles[it]) }
    }
}
