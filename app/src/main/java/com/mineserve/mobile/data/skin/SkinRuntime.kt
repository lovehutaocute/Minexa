package com.mineserve.mobile.data.skin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** 已按 light/dark 解析完成的扩展色，组件直接取，不再判断深浅。 */
@Immutable
data class ExtColors(
    val glassTop: Color, val glassMid: Color, val glassBottom: Color,
    val glassRim: Color, val cardRim: Color,
    val pillStart: Color, val pillEnd: Color,
    val navActiveStart: Color, val navActiveEnd: Color,
    val heroTrack: Color, val heroBarStart: Color, val heroBarEnd: Color,
    val heroOnDim: Color, val heroOnSoft: Color,
    val inkTitle: Color, val inkBody: Color, val inkHint: Color,
    val night: Color, val deep: Color, val hydro: Color,
    val gold: Color, val goldInk: Color,
    val topGlow: Color, val navSurface: Color,
    /**
     * 主题包是否**显式**给了 `gold`。
     *
     * 区分这一点是为了「金线描边」这类强调装饰：只有作者主动写了金色才画，
     * 否则默认主题（gold 兜底成 tertiary 红橙）会给按钮套一圈红边。
     */
    val hasExplicitGold: Boolean = false
)

/** 解码后的主题图片。任一槽位缺失都是 null —— 组件负责降级，不允许出现透明底。 */
data class SkinAssets(
    val background: ImageBitmap? = null,
    val hero: ImageBitmap? = null,
    val accent: ImageBitmap? = null,
    /**
     * 背景图的**预模糊**小图（约 1/18 边长，几十 KB）。
     *
     * 毛玻璃面板按自身在屏幕上的位置采样这张图并放大绘制 —— 放大本身带插值，
     * 于是一块面板背后就是「该位置真实背景的模糊版」。模糊在切主题时算一次，
     * 滚动时只做一次纹理采样，老机器也不会掉帧（不碰 RenderEffect/RenderScript）。
     */
    val blurredBackground: ImageBitmap? = null,
    /**
     * 立绘是否允许**探出卡片边界**（`manifest.assetsMeta.hero.scale == "overflow"`）。
     *
     * 默认 false = 铺满卡片并裁切（老包的行为）；true 时 hero 图按卡片高度等比放大后
     * 居中靠右绘制，不再被卡片圆角裁掉，角色可以伸到卡片外面。
     */
    val heroOverflow: Boolean = false,
    /** 启动条插画同上（`assetsMeta.accent.scale == "overflow"`）。 */
    val accentOverflow: Boolean = false,
    /**
     * 组件贴图：槽位名 → 已解码的图。
     *
     * 这是 v2 的能力：主题包除了「配色 + 3 张整图」，还能给单个组件贴皮肤。
     * 槽位名必须在 [SkinTileSlots.ALL] 里（白名单），未知键一律忽略 ——
     * 否则一个恶意包可以用任意 key 把图片塞进内存。
     */
    val tiles: Map<String, SkinTile> = emptyMap(),
    /**
     * 深色模式专用贴图（`manifest.assetsDark`）。
     *
     * 贴图是烘焙了颜色的位图，浅色那套在深色模式下是白板 + 浅字。
     * 空表就代表作者没给，全走 [tiles]（老包行为不变）。
     */
    val tilesDark: Map<String, SkinTile> = emptyMap()
) {
    /** Hero 原图宽高。缺图时为 null，调用方回退到设计稿的 216/418 比例。 */
    val heroMeta: Pair<Int, Int>? get() = hero?.let { it.width to it.height }
}

/**
 * 一张组件贴图。
 *
 * 边距**保存为 dp**（就是主题包里写的那个数），两套尺度在绘制时分头换算：
 * 切图用「图像素 = dp × 图宽/设计宽」，落到画布用「px = dp × density」。
 *
 * 这两个尺度混用是必翻车的：图是 3x 的，拿 3x 的像素数当目标像素画，
 * 2.75 倍屏上四角只剩 1/3 大小，中间拉伸带把装饰一起拽变形
 * —— 表现就是「边框像被压扁的牌子」。
 *
 * 四个都为 0 表示整图拉伸。
 */
@Immutable
data class SkinTile(
    val bitmap: ImageBitmap,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    /** `assetsMeta.width/height`：设计尺寸 dp，缺省 0 表示没声明，退化成整图拉伸。 */
    val designWidth: Float = 0f,
    val designHeight: Float = 0f,
    /** 开则只取形状、按语义色染色（见 `drawSkinTile`）。 */
    val tint: Boolean = false
) {
    val isNinePatch: Boolean get() = left + top + right + bottom > 0f

    /** dp 边距 → 源图像素（切九宫格用）。 */
    fun srcInsets(): FloatArray {
        if (designWidth <= 0f || designHeight <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val sx = bitmap.width / designWidth
        val sy = bitmap.height / designHeight
        return floatArrayOf(left * sx, top * sy, right * sx, bottom * sy)
    }

    /** dp 边距 → 目标像素（画到画布用）。 */
    fun dstInsets(density: Float): FloatArray =
        floatArrayOf(left * density, top * density, right * density, bottom * density)
}

/**
 * 允许的组件贴图槽位。
 *
 * 加一个槽位 = 在这里加一行 + 在组件上写 `Modifier.skinSurface("新槽位", ...)`。
 * 白名单是安全边界：包里的 `assets` 只认这些键，其余键连解码都不会做。
 */
object SkinTileSlots {
    const val CARD = "card"                  // 所有 McCard（列表卡 / 地址卡 / 设置卡）
    const val CARD_COMPACT = "cardCompact"   // 紧凑卡（行内分组）
    const val START_BUTTON = "startButton"   // 概览页启动长条（未运行态）
    const val STOP_BUTTON = "stopButton"     // 运行中的停止 / 强停
    const val PILL_BUTTON = "pillButton"     // 「依赖与环境管理」
    const val ROW_BUTTON = "rowButton"       // 整行长条按钮
    const val PRIMARY_BUTTON = "primaryButton"
    const val HERO_CARD = "heroCard"
    const val INPUT_FIELD = "inputField"
    const val HEADER = "header"
    const val NAVBAR = "navbar"
    const val DIALOG = "dialog"
    const val BANNER = "banner"
    const val CHIP = "chip"

    val ALL = setOf(
        CARD, CARD_COMPACT, START_BUTTON, STOP_BUTTON, PILL_BUTTON, ROW_BUTTON,
        PRIMARY_BUTTON, HERO_CARD, INPUT_FIELD, HEADER, NAVBAR, DIALOG, BANNER, CHIP
    )
}

/** 一次 resolve 的结果：令牌已经展开成 Compose 能直接用的对象。 */
@Immutable
data class SkinResolved(
    val id: String,
    val name: String,
    val scheme: ColorScheme,
    val ext: ExtColors,
    val shape: ShapeTokens,
    val metrics: MetricTokens,
    val typography: TypographyTokens,
    val assets: SkinAssets,
    val isBuiltin: Boolean,
    /** 当前解析的是不是深色那一套（贴图要按它挑两份里的哪一份）。 */
    val isDark: Boolean = false,
    /** 仅用于 [SkinRuntime] 内部缓存判定，UI 不要读。 */
    val cacheKey: Pair<Boolean, Int> = false to 0
)

/**
 * 一个已安装主题的运行时视图：令牌 + 目录 + 解码后的图片缓存。
 *
 * 图片必须解码一次就缓存住：背景 1080×2378 的 ARGB_8888 约 10MB，每帧解码必 OOM。
 * 切主题时整个 SkinRuntime 被替换，旧实例连同位图一起被 GC —— 不要把它塞进静态集合里留住历史。
 */
class SkinRuntime private constructor(
    val dir: File?,
    val manifest: SkinManifest,
    val tokens: SkinTokens,
    val isBuiltin: Boolean
) {
    private var cached: SkinResolved? = null
    private var cachedWidth = 0
    private var cachedAssets: SkinAssets = SkinAssets()

    /** 是否为 [SkinRuntime.builtin] 生成的兜底实例（无目录、无令牌）。 */
    val isFallback: Boolean get() = dir == null

    /**
     * 在 IO 线程上先把图片解码好。
     *
     * `resolve()` 是在组合里同步调用的：不预热的话，切主题那一下会在主线程解码
     * 1080×2378 的背景图（40–80ms），肉眼就是一次掉帧。仓库在 `refresh()`（IO）里调它。
     */
    fun prewarm(targetWidthPx: Int) {
        if (targetWidthPx > 0) assetsFor(targetWidthPx)
    }

    fun resolve(dark: Boolean, targetWidthPx: Int): SkinResolved {
        val key = dark to targetWidthPx
        cached?.let { if (it.cacheKey == key) return it }
        return build(dark, targetWidthPx).also { cached = it }
    }

    private fun build(dark: Boolean, targetWidthPx: Int): SkinResolved {
        val tokens = this.tokens
        val useDark = dark && manifest.supportsDark
        val base = if (useDark) darkColorScheme() else lightColorScheme()
        val roles = (if (useDark) tokens.color.dark else tokens.color.light) ?: ColorRoleTokens()
        val ext = (if (useDark) tokens.ext.dark else tokens.ext.light) ?: ExtTokens()

        val scheme = base.copy(
            primary = roles.primary.colorOr(base.primary),
            onPrimary = roles.onPrimary.colorOr(base.onPrimary),
            primaryContainer = roles.primaryContainer.colorOr(base.primaryContainer),
            onPrimaryContainer = roles.onPrimaryContainer.colorOr(base.onPrimaryContainer),
            secondary = roles.secondary.colorOr(base.secondary),
            onSecondary = roles.onSecondary.colorOr(base.onSecondary),
            secondaryContainer = roles.secondaryContainer.colorOr(base.secondaryContainer),
            onSecondaryContainer = roles.onSecondaryContainer.colorOr(base.onSecondaryContainer),
            tertiary = roles.tertiary.colorOr(base.tertiary),
            onTertiary = roles.onTertiary.colorOr(base.onTertiary),
            tertiaryContainer = roles.tertiaryContainer.colorOr(base.tertiaryContainer),
            onTertiaryContainer = roles.onTertiaryContainer.colorOr(base.onTertiaryContainer),
            background = roles.background.colorOr(base.background),
            onBackground = roles.onBackground.colorOr(base.onBackground),
            surface = roles.surface.colorOr(base.surface),
            onSurface = roles.onSurface.colorOr(base.onSurface),
            surfaceVariant = roles.surfaceVariant.colorOr(base.surfaceVariant),
            onSurfaceVariant = roles.onSurfaceVariant.colorOr(base.onSurfaceVariant),
            outline = roles.outline.colorOr(base.outline),
            outlineVariant = roles.outlineVariant.colorOr(base.outlineVariant),
            surfaceTint = roles.surfaceTint.colorOr(base.surfaceTint),
            surfaceContainerLowest = roles.surfaceContainerLowest.colorOr(base.surfaceContainerLowest),
            surfaceContainerLow = roles.surfaceContainerLow.colorOr(base.surfaceContainerLow),
            surfaceContainer = roles.surfaceContainer.colorOr(base.surfaceContainer),
            surfaceContainerHigh = roles.surfaceContainerHigh.colorOr(base.surfaceContainerHigh),
            surfaceContainerHighest = roles.surfaceContainerHighest.colorOr(base.surfaceContainerHighest),
            surfaceDim = roles.surfaceDim.colorOr(base.surfaceDim),
            surfaceBright = roles.surfaceBright.colorOr(base.surfaceBright),
            inverseSurface = roles.inverseSurface.colorOr(base.inverseSurface),
            inverseOnSurface = roles.inverseOnSurface.colorOr(base.inverseOnSurface),
            inversePrimary = roles.inversePrimary.colorOr(base.inversePrimary),
            scrim = roles.scrim.colorOr(base.scrim)
        )

        return SkinResolved(
            id = manifest.id, name = manifest.name, scheme = scheme,
            ext = ExtColors(
                glassTop = ext.glassTop.colorOr(scheme.surface),
                glassMid = ext.glassMid.colorOr(scheme.surface),
                glassBottom = ext.glassBottom.colorOr(scheme.surface),
                glassRim = ext.glassRim.colorOr(Color.White),
                cardRim = ext.cardRim.colorOr(scheme.outlineVariant),
                pillStart = ext.pillStart.colorOr(scheme.primary),
                pillEnd = ext.pillEnd.colorOr(scheme.primary),
                navActiveStart = ext.navActiveStart.colorOr(scheme.primaryContainer),
                navActiveEnd = ext.navActiveEnd.colorOr(scheme.primaryContainer),
                // Hero 卡永远是深底 + 白字（无论浅色主题还是深色主题），所以这几档的兜底
                // 必须是白色系而不是 surface 系 —— 用 onSurfaceVariant 会在靛蓝渐变上变成灰字。
                heroTrack = ext.heroTrack.colorOr(Color.White.copy(alpha = 0.22f)),
                heroBarStart = ext.heroBarStart.colorOr(scheme.secondary),
                heroBarEnd = ext.heroBarEnd.colorOr(scheme.secondary),
                heroOnDim = ext.heroOnDim.colorOr(Color.White.copy(alpha = 0.7f)),
                heroOnSoft = ext.heroOnSoft.colorOr(Color.White.copy(alpha = 0.9f)),
                inkTitle = ext.inkTitle.colorOr(scheme.onSurface),
                inkBody = ext.inkBody.colorOr(scheme.onSurfaceVariant),
                inkHint = ext.inkHint.colorOr(scheme.onSurfaceVariant),
                night = ext.night.colorOr(scheme.inverseSurface),
                deep = ext.deep.colorOr(scheme.scrim),
                hydro = ext.hydro.colorOr(scheme.secondary),
                gold = ext.gold.colorOr(scheme.tertiary),
                goldInk = ext.goldInk.colorOr(scheme.tertiary),
                hasExplicitGold = ext.gold != null,
                topGlow = ext.topGlow.colorOr(Color.Transparent),
                navSurface = ext.navSurface.colorOr(scheme.surface)
            ),
            shape = tokens.shape, metrics = tokens.metrics, typography = tokens.typography,
            assets = assetsFor(targetWidthPx), isBuiltin = isBuiltin, isDark = dark,
            cacheKey = dark to targetWidthPx
        )
    }

    @Synchronized
    private fun assetsFor(targetWidthPx: Int): SkinAssets {
        if (targetWidthPx == cachedWidth) return cachedAssets
        val dir = dir ?: return SkinAssets()          // 兜底主题无目录 → 无图
        val background = decode(File(dir, manifest.assets["background"].orEmpty()), targetWidthPx)
        cachedAssets = SkinAssets(
            background = background,
            hero = decode(File(dir, manifest.assets["hero"].orEmpty()), targetWidthPx),
            accent = decode(File(dir, manifest.assets["accent"].orEmpty()), targetWidthPx),
            blurredBackground = background?.let { blurDownscale(it) },
            heroOverflow = manifest.assetsMeta["hero"]?.scale == "overflow",
            accentOverflow = manifest.assetsMeta["accent"]?.scale == "overflow",
            tiles = decodeTiles(dir, manifest.assets, manifest.assetsMeta),
            tilesDark = decodeTiles(dir, manifest.assetsDark, manifest.assetsMetaDark)
        )
        cachedWidth = targetWidthPx
        return cachedAssets
    }

    /**
     * 解码组件贴图（`assets` 里除 3 个整图槽位之外的键）。
     *
     * 三道闸门，少一道就会出现「点了导入 App 直接 OOM」：
     * 1. 槽位必须在 [SkinTileSlots.ALL] 白名单里；
     * 2. 路径必须落在包目录内（挡住 `../../` 这类越界）；
     * 3. 按 [TILE_MAX_PX] 降采样，避免 4K 贴图带进内存。
     */
    private fun decodeTiles(
        dir: File,
        paths: Map<String, String>,
        metas: Map<String, AssetMeta>
    ): Map<String, SkinTile> {
        if (paths.isEmpty()) return emptyMap()
        val out = HashMap<String, SkinTile>()
        for ((slot, path) in paths) {
            if (slot !in SkinTileSlots.ALL || path.isBlank()) continue
            val file = resolveInside(dir, path) ?: continue
            val bmp = decode(file, TILE_MAX_PX) ?: continue
            val meta = metas[slot]
            val np = meta?.ninePatch
            val dw = meta?.width ?: 0
            val dh = meta?.height ?: 0
            val base = SkinTile(bmp, tint = meta?.tint == true, designWidth = dw.toFloat(), designHeight = dh.toFloat())
            if (np == null || np.size != 4 || dw <= 0 || dh <= 0) {
                out[slot] = base
                continue
            }
            // 边距保持 dp（包里写什么就是什么），换算交给绘制阶段：
            // 切图要图像素、落画布要 density，两边混用就会把四角压扁。
            val l = np[0].coerceAtLeast(0f)
            val tp = np[1].coerceAtLeast(0f)
            val r = np[2].coerceAtLeast(0f)
            val b = np[3].coerceAtLeast(0f)
            // 两条边加起来吃掉整张图 = 切不动，退化成整图拉伸。
            out[slot] = if (l + r >= dw || tp + b >= dh) base else base.copy(left = l, top = tp, right = r, bottom = b)
        }
        return out
    }

    /** 把 manifest 里的相对路径解析成包内文件；越界或不存在返回 null。 */
    private fun resolveInside(dir: File, rel: String): File? = runCatching {
        val root = dir.canonicalFile
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.path + File.separator)) return null
        if (!f.isFile) return null
        f
    }.getOrNull()

    /**
     * 把背景缩到 1/[BLUR_DIVISOR] 边长，得到「毛玻璃用的底图」。
     *
     * 为什么这样做就是模糊：双线性缩小会把每个输出像素变成一片区域的平均色（盒式模糊），
     * 绘制时再按面板尺寸放大，插值会把这层低频信息还原成一片柔和的色晕 —— 这正是磨砂玻璃
     * 透出来的东西。分两级缩小（先 1/4 再 1/[BLUR_DIVISOR]）比一次缩到位更接近高斯模糊，
     * 也避免一次大跨度采样产生摩尔纹。
     *
     * 代价：一张 1080×2378 的图变成 60×132（约 32KB），绘制时是一次纹理采样，
     * 与画一个纯色块同量级 —— 这是它比 RenderEffect 更合适低端机的原因。
     */
    private fun blurDownscale(src: ImageBitmap): ImageBitmap? = runCatching {
        val bmp = src.asAndroidBitmap()
        val targetW = (bmp.width / BLUR_DIVISOR).coerceAtLeast(8)
        val targetH = ((bmp.height.toFloat() * targetW / bmp.width).toInt()).coerceAtLeast(8)
        val quarterW = (bmp.width / 4).coerceAtLeast(targetW)
        val quarterH = ((bmp.height.toFloat() * quarterW / bmp.width).toInt()).coerceAtLeast(targetH)
        val quarter = Bitmap.createScaledBitmap(bmp, quarterW, quarterH, true)
        val blurred = Bitmap.createScaledBitmap(quarter, targetW, targetH, true)
        if (quarter !== blurred) quarter.recycle()
        blurred.asImageBitmap()
    }.getOrNull()

    /** 单次降采样解码。bounds 预检失败/文件不存在一律返回 null，由调用方降级。 */
    private fun decode(file: File, targetW: Int): ImageBitmap? {
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && sample < 8) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
        }.getOrNull()
    }

    companion object {
        /** assets 里预置的包，id 固定，不允许用户包同名覆盖。 */
        val BUILTIN_IDS = setOf("firefly-pearl", "mist-neutral")

        /**
         * 毛玻璃底图的缩小倍数。18 → 1080 宽的图剩 60px 宽，
         * 放大到卡片尺寸后相当于 30px 左右半径的模糊，与「磨砂」观感匹配；
         * 再大就会出现明显色块，再小则模糊不足、透得出细节。
         */
        private const val BLUR_DIVISOR = 18

        /**
         * 组件贴图的最长边上限（像素）。
         *
         * 贴图是「窗口里的一块」，不需要 4K：按钮按 328dp 宽算，3 倍图 984px 已经够锐。
         * 超过就降采样，否则一个包塞 14 张 4096×4096 的图能直接把内存打满。
         */
        private const val TILE_MAX_PX = 1024

        /** 默认生效的内置主题（首启）。 */
        const val DEFAULT_BUILTIN_ID = "mist-neutral"

        /** 解析一个解包后的主题目录。JSON 非法 / id 非法时返回 null。 */
        fun load(dir: File, isBuiltin: Boolean = false): SkinRuntime? = runCatching {
            val manifest = SkinPack.parseManifest(File(dir, "manifest.json").readText())
            require(SkinPack.validId(manifest.id))
            val tokens = SkinPack.parseTokens(File(dir, manifest.tokens.ifBlank { "tokens.json" }).readText())
            SkinRuntime(dir, manifest, tokens, isBuiltin)
        }.getOrNull()

        /**
         * 兜底主题：M3 默认配色、无图。只在「首启 bootstrap 尚未完成」的极短窗口内出现，
         * 必须保证它绝不抛异常，否则冷启动会白屏。
         */
        fun builtin() = SkinRuntime(
            dir = null,
            manifest = SkinManifest(
                schemaVersion = 1, id = "builtin", name = "默认",
                version = "1", author = "Minexa", minAppVersionCode = 1
            ),
            tokens = SkinTokens(),
            isBuiltin = true
        )
    }
}
