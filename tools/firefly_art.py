"""「萤火 · 白蝶」的组件贴图合成器。

为什么不再直接拿生图模型出的组件图当贴图：

1. 那些图里**烧了假 UI**（绿点、进度条、假标题条）。抹掉之后在深色卡上留下
   一块浅灰斑 —— 就是状态卡顶部那块白疤。
2. 装饰画得比控件还大，九宫格切不到，拉伸后蝶翅直接变成畸形。
3. 每块面板都带一圈深色描边，整屏看起来像一排奖牌框。

所以这里改成**程序化合成**：平涂/细渐变 + 1dp 描边 + 角上一枚小蝶翼水印。
所有几何都在设计尺寸（dp × 3）上画，装饰塞进圆角那一格里，九宫格切到它不会变形。

用法：
    from firefly_art import render_tile
    render_tile('card', 328, 96, 18, STYLES['card'])
"""
from PIL import Image, ImageChops, ImageDraw, ImageFilter

SCALE = 3  # 出图倍率，和 assetsMeta 的 width/height（dp）配套

MINT = (150, 236, 219)
MINT_DEEP = (86, 176, 170)
PEARL = (255, 255, 255)


def _vgrad(w, h, top, bottom, alpha=255):
    img = Image.new('RGBA', (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c + (alpha,))
    return img


def _rounded_mask(w, h, r):
    m = Image.new('L', (w * 4, h * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * 4 - 1, h * 4 - 1], radius=r * 4, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def _wing(canvas, k, cx, cy, rx, ry, angle, c_in, c_out, alpha):
    pad = 6
    w, h = int(rx * 2) + pad * 2, int(ry * 2) + pad * 2
    layer = _vgrad(w, h, c_in, c_out, alpha)
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).ellipse([pad, pad, w - pad - 1, h - pad - 1], fill=255)
    layer.putalpha(m)
    # 翅膀边缘一圈更亮的描边，低透明度下也看得出轮廓
    edge = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(edge).ellipse([pad, pad, w - pad - 1, h - pad - 1],
                                 outline=(255, 255, 255, int(alpha * 0.55)),
                                 width=max(1, int(k * 1.6)))
    layer = Image.alpha_composite(layer, edge)
    layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    canvas.alpha_composite(layer, (int(cx - layer.width / 2), int(cy - layer.height / 2)))


def butterfly(px=256, alpha=255):
    """薄荷→珍珠白的蝶翼徽记。无水印、无假 UI，纯几何。"""
    c = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    k = px / 256.0
    cx, cy = px * 0.5, px * 0.54
    # 触须（画在翅下，末端露在外面）
    ant = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    ad = ImageDraw.Draw(ant)
    for sx in (-1, 1):
        ad.arc([cx + (sx * 2 - 30) * k, cy - 108 * k,
                cx + (sx * 2 + 30) * k, cy - 40 * k],
               200 if sx < 0 else 250, 340 if sx < 0 else 30,
               fill=(214, 248, 243, int(alpha * 0.85)), width=max(1, int(2.2 * k)))
    c.alpha_composite(ant)
    # 上翅 / 下翅
    for sx in (-1, 1):
        _wing(c, k, cx + sx * 46 * k, cy - 34 * k, 40 * k, 54 * k, -24 * sx, PEARL, MINT, alpha)
    for sx in (-1, 1):
        _wing(c, k, cx + sx * 33 * k, cy + 40 * k, 28 * k, 40 * k, 26 * sx, MINT, PEARL, int(alpha * 0.92))
    # 身体
    body = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.ellipse([cx - 3.4 * k, cy - 52 * k, cx + 3.4 * k, cy + 56 * k], fill=(240, 253, 251, alpha))
    bd.ellipse([cx - 1.4 * k, cy - 42 * k, cx + 1.4 * k, cy + 46 * k],
               fill=(120, 202, 194, int(alpha * 0.75)))
    c.alpha_composite(body)
    # 外发光
    glow = c.filter(ImageFilter.GaussianBlur(7 * k))
    tint = Image.new('RGBA', (px, px), MINT_DEEP + (0,))
    tint.putalpha(glow.getchannel('A').point(lambda v: int(v * 0.40)))
    out = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    out.alpha_composite(tint)
    out.alpha_composite(c)
    return out


def render_tile(w_dp, h_dp, radius_dp, top, bottom, rim, alpha=255,
                orn_alpha=0.30, orn_dp=None, sheen=True):
    """按设计尺寸画一块面板。

    `orn_dp` 是水印蝶翼的边长（dp）。默认取圆角半径，这样它整枚落在九宫格
    的右上/右下角格里，拉伸时不会被拽变形。
    """
    w, h = int(round(w_dp * SCALE)), int(round(h_dp * SCALE))
    r = int(round(radius_dp * SCALE))
    img = _vgrad(w, h, top, bottom, alpha)
    mask = _rounded_mask(w, h, r)
    img.putalpha(mask)

    if orn_alpha > 0:
        size_dp = orn_dp if orn_dp is not None else radius_dp
    else:
        size_dp = 0
    if size_dp >= 6:
        o = butterfly(int(round(size_dp * SCALE)), 255)
        o.putalpha(o.getchannel('A').point(lambda v: int(v * orn_alpha)))
        layer = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        # 右下角，留 2dp 边距；整枚塞进圆角格子里
        layer.alpha_composite(o, (w - o.width - int(2 * SCALE), h - o.height - int(2 * SCALE)))
        layer.putalpha(ImageChops.multiply(layer.getchannel('A'), mask))
        img = Image.alpha_composite(img, layer)

    d = ImageDraw.Draw(img)
    lw = max(1, int(round(SCALE * 0.9)))
    inset = SCALE * 0.5
    d.rounded_rectangle([inset, inset, w - 1 - inset, h - 1 - inset],
                        radius=r, outline=rim + (215,), width=lw)

    if sheen:
        # 顶部一层极淡的高光，玻璃感靠它，不靠深描边。
        # 必须是**无边界**的竖向渐变：画一个圆角矩形再模糊，会在卡片里显出一个
        # 内嵌的浅色「小牌子」，那正是要消掉的观感。
        hl = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        hd = ImageDraw.Draw(hl)
        span = max(1, int(h * 0.55))
        for y in range(span):
            t = y / span
            a = int(26 * (1 - t) ** 1.8)
            if a <= 0:
                continue
            hd.line([(0, y), (w, y)], fill=(255, 255, 255, a))
        hl.putalpha(ImageChops.multiply(hl.getchannel('A'), mask))
        img = Image.alpha_composite(img, hl)
    img.putalpha(Image.composite(img.getchannel('A'), Image.new('L', (w, h), 0), mask))
    return img


# 每个槽位的配色（light / dark 两套，暗色用同一套几何换个底色）
LIGHT_STYLES = {
    'card':        dict(top=(255, 255, 255), bottom=(242, 247, 248), rim=(213, 226, 236), orn_alpha=0.26),
    'cardCompact': dict(top=(255, 255, 255), bottom=(245, 249, 250), rim=(216, 228, 238), orn_alpha=0.20),
    'dialog':      dict(top=(255, 255, 255), bottom=(240, 246, 247), rim=(208, 222, 234), orn_alpha=0.22),
    'inputField':  dict(top=(255, 255, 255), bottom=(245, 249, 250), rim=(203, 217, 228), orn_alpha=0.0),
    'banner':      dict(top=(252, 254, 254), bottom=(236, 245, 244), rim=(206, 224, 224), orn_alpha=0.18),
    'chip':        dict(top=(207, 242, 229), bottom=(183, 235, 221), rim=(159, 220, 203), orn_alpha=0.0),
    'header':      dict(top=(255, 255, 255), bottom=(244, 249, 249), rim=(219, 231, 236), orn_alpha=0.14),
    'navbar':      dict(top=(251, 250, 247), bottom=(238, 245, 245), rim=(214, 227, 231), orn_alpha=0.16),
    'rowButton':   dict(top=(255, 255, 255), bottom=(244, 248, 249), rim=(214, 227, 236), orn_alpha=0.12),
    'startButton': dict(top=(35, 196, 168), bottom=(16, 146, 126), rim=(14, 122, 106), orn_alpha=0.22),
    'stopButton':  dict(top=(242, 133, 124), bottom=(211, 84, 74), rim=(184, 66, 58), orn_alpha=0.18),
    'primaryButton': dict(top=(46, 62, 124), bottom=(29, 42, 88), rim=(74, 95, 158), orn_alpha=0.24),
    'pillButton':  dict(top=(44, 60, 120), bottom=(27, 42, 86), rim=(72, 92, 154), orn_alpha=0.22),
    'heroCard':    dict(top=(44, 62, 123), bottom=(26, 41, 84), rim=(74, 95, 158), orn_alpha=0.34),
}

DARK_STYLES = {
    'card':        dict(top=(25, 38, 63), bottom=(18, 29, 51), rim=(48, 66, 98), orn_alpha=0.20),
    'cardCompact': dict(top=(25, 38, 63), bottom=(19, 30, 52), rim=(46, 63, 94), orn_alpha=0.16),
    'dialog':      dict(top=(24, 36, 60), bottom=(17, 27, 48), rim=(52, 70, 104), orn_alpha=0.18),
    'inputField':  dict(top=(22, 34, 57), bottom=(18, 28, 48), rim=(46, 64, 96), orn_alpha=0.0),
    'banner':      dict(top=(24, 38, 60), bottom=(17, 28, 47), rim=(44, 62, 92), orn_alpha=0.14),
    'chip':        dict(top=(18, 62, 54), bottom=(14, 48, 42), rim=(24, 88, 76), orn_alpha=0.0),
    'header':      dict(top=(20, 31, 52), bottom=(15, 24, 42), rim=(40, 56, 84), orn_alpha=0.12),
    'navbar':      dict(top=(21, 33, 55), bottom=(15, 24, 42), rim=(40, 56, 84), orn_alpha=0.14),
    'rowButton':   dict(top=(23, 36, 59), bottom=(17, 27, 47), rim=(44, 62, 93), orn_alpha=0.10),
    'startButton': dict(top=(26, 132, 112), bottom=(14, 98, 84), rim=(20, 116, 98), orn_alpha=0.20),
    'stopButton':  dict(top=(150, 66, 60), bottom=(112, 46, 42), rim=(148, 74, 66), orn_alpha=0.16),
    'primaryButton': dict(top=(52, 72, 140), bottom=(35, 50, 100), rim=(78, 100, 166), orn_alpha=0.22),
    'pillButton':  dict(top=(50, 70, 136), bottom=(34, 49, 96), rim=(76, 98, 162), orn_alpha=0.20),
    'heroCard':    dict(top=(30, 44, 90), bottom=(18, 30, 62), rim=(58, 78, 132), orn_alpha=0.30),
}
