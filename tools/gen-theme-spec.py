#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「主题包可替换项对照表」：同时输出 SVG 与 PNG（同一套布局，避免两份不一致）。

这不是界面导览图，而是给做主题的人查的表：
每个能改的键 -> 它落在哪个界面元素上 -> 默认值是多少 -> 现在到底有没有生效。

用法：
    python tools/gen-theme-spec.py
输出：
    docs/theme-spec.svg
    docs/theme-spec.png
"""

from __future__ import annotations

import os

# ── 画布 ───────────────────────────────────────────────────────
W = 1680
M = 56
CW = W - M * 2                  # 1568
COL_W = (CW - 24) // 2          # 772
COL1_X = M + 18                 # 栏内绘图起点
COL2_X = M + COL_W + 24 + 18
IN_W = COL_W - 36               # 栏内可用宽 736
ROW_H = 44

BG = "#F5F6FA"
INK = "#1C2333"
BODY = "#3D4759"
MUTED = "#737E92"
FAINT = "#9AA3B3"
PRIMARY = "#3B4C9C"
CARD = "#FFFFFF"
RIM = "#E3E7EF"
OK = "#1F9D68"
OK_BG = "#E7F6EF"
PEND = "#8A93A5"
PEND_BG = "#EFF1F5"
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
MONO_F = "C:/Windows/Fonts/consola.ttf"
MONO_FB = "C:/Windows/Fonts/consolab.ttf"

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs")


class Svg:
    def __init__(self):
        self.parts = []

    def rect(self, x, y, w, h, fill, rx=0, stroke=None, sw=0, opacity=1.0):
        s = f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" fill="{fill}"'
        if opacity != 1.0:
            s += f' fill-opacity="{opacity}"'
        if stroke:
            s += f' stroke="{stroke}" stroke-width="{sw}"'
        self.parts.append(s + "/>")

    def line(self, x1, y1, x2, y2, color, w=2):
        self.parts.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="{color}" stroke-width="{w}"/>'
        )

    def circle(self, cx, cy, r, fill, opacity=1.0):
        o = f' fill-opacity="{opacity}"' if opacity != 1.0 else ""
        self.parts.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{fill}"{o}/>')

    def text(self, x, y, s, size, color, bold=False, anchor="start", mono=False, opacity=1.0):
        a = {"start": "start", "middle": "middle", "end": "end"}[anchor]
        extra = ' font-weight="700"' if bold else ""
        if opacity != 1.0:
            extra += f' fill-opacity="{opacity}"'
        fam = "Consolas, Menlo, monospace" if mono else "Microsoft YaHei, PingFang SC, sans-serif"
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        # 两个后端统一把 y 当「基线再上移 0.78em」：PIL 的 anchor="ls" 已经是基线，
        # 这里同步减掉，SVG 与 PNG 才会逐像素对齐（否则 SVG 每行都低约一个字高）。
        self.parts.append(
            f'<text x="{x:.1f}" y="{y - size * 0.78:.1f}" font-family="{fam}" '
            f'font-size="{size}" fill="{color}" text-anchor="{a}"{extra}>{s}</text>'
        )

    def save(self, path, h):
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" '
                f'viewBox="0 0 {W} {h}">')
        with open(path, "w", encoding="utf-8") as f:
            f.write(head + "".join(self.parts) + "</svg>")


class Png:
    def __init__(self, h):
        from PIL import Image, ImageDraw

        self.img = Image.new("RGB", (W, h), BG)
        self.d = ImageDraw.Draw(self.img)
        self._Image = Image
        self._fonts = {}

    def _font(self, size, bold, mono):
        key = (round(size), bold, mono)
        if key not in self._fonts:
            from PIL import ImageFont

            path = (MONO_FB if bold else MONO_F) if mono else (FONT_B if bold else FONT)
            self._fonts[key] = ImageFont.truetype(path, round(size))
        return self._fonts[key]

    def rect(self, x, y, w, h, fill, rx=0, stroke=None, sw=0, opacity=1.0):
        if opacity >= 1.0:
            self.d.rounded_rectangle([x, y, x + w, y + h], radius=rx, fill=fill,
                                     outline=stroke, width=int(sw) if sw else 0)
        else:
            layer = self._Image.new("RGBA", (max(1, int(w)), max(1, int(h))), (0, 0, 0, 0))
            from PIL import ImageDraw

            dd = ImageDraw.Draw(layer)
            rgb = tuple(int(fill[i:i + 2], 16) for i in (1, 3, 5))
            dd.rounded_rectangle([0, 0, w, h], radius=rx, fill=(*rgb, int(255 * opacity)))
            self.img.paste(layer, (int(x), int(y)), layer)

    def line(self, x1, y1, x2, y2, color, w=2):
        self.d.line([(x1, y1), (x2, y2)], fill=color, width=int(w))

    def circle(self, cx, cy, r, fill, opacity=1.0):
        self.d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill)

    def text(self, x, y, s, size, color, bold=False, anchor="start", mono=False, opacity=1.0):
        f = self._font(size, bold, mono)
        a = {"start": "ls", "middle": "ms", "end": "rs"}[anchor]
        self.d.text((x, y - size * 0.78), s, font=f, fill=color, anchor=a)

    def save(self, path, h):
        self.img.save(path)


# ── 数据 ───────────────────────────────────────────────────────
COLORS = [
    ("primary", "主按钮 / 选中文字 / 进度条", "#3B4C9C", "#9FB0F0", 1),
    ("onPrimary", "主色块上的文字与图标", "#FFFFFF", "#1B2447", 1),
    ("primaryContainer", "淡主色底：选中胶囊 / 次按钮", "#EEF0FA", "#2A3563", 1),
    ("onPrimaryContainer", "淡主色底上的文字", "#3B4C9C", "#D5DCFA", 1),
    ("secondary", "成功绿：运行中 / 完成 / 内存条", "#2FBF87", "#8CF0C4", 1),
    ("onSecondary", "绿底上的文字", "#FFFFFF", "#06301F", 1),
    ("secondaryContainer", "绿淡底：状态胶囊 / 成功条", "#E7F8F0", "#17382C", 1),
    ("onSecondaryContainer", "绿淡底上的文字", "#2FBF87", "#8CF0C4", 1),
    ("tertiary", "危险红：停止 / 删除 / 报错", "#F97066", "#FFB4A8", 1),
    ("onTertiary", "红底上的文字", "#FFFFFF", "#561E1A", 1),
    ("tertiaryContainer", "红淡底：警告条 / 危险行", "#FDEEEC", "#3D2424", 1),
    ("onTertiaryContainer", "红淡底上的文字", "#F97066", "#FFB4A8", 1),
    ("background", "页面底色（内容区）", "#F3F4F8", "#10131A", 1),
    ("onBackground", "正文与页面标题", "#1F2430", "#E6E9F2", 1),
    ("surface", "卡片 / 顶栏 / 底栏底", "#FFFFFF", "#1A1F2B", 1),
    ("onSurface", "卡片内标题与正文", "#1F2430", "#E6E9F2", 1),
    ("surfaceVariant", "输入框 / 轨道 / 字段底", "#F0F1F5", "#232936", 1),
    ("onSurfaceVariant", "次要说明文字（Muted）", "#6B7385", "#9AA3B5", 1),
    ("outline", "分割线 / 输入框描边", "#E4E7EE", "#2A3140", 1),
    ("outlineVariant", "卡片 1dp 描边（Line）", "#E4E7EE", "#2A3140", 1),
    ("surfaceTint", "抬升叠色（本 App 不用）", "#3B4C9C", "#9FB0F0", 1),
    ("surfaceContainerLowest", "最底层容器", "#FFFFFF", "#0B0E14", 1),
    ("surfaceContainerLow", "低层容器", "#FAFBFC", "#161B25", 1),
    ("surfaceContainer", "默认容器层", "#F7F8FA", "#1A1F2B", 1),
    ("surfaceContainerHigh", "高层容器", "#F5F6F9", "#20262F", 1),
    ("surfaceContainerHighest", "最高层容器 / 弹层底", "#F0F1F5", "#262C38", 1),
    ("surfaceDim", "下沉区 / 禁用块", "#E9EBF1", "#10131A", 1),
    ("surfaceBright", "抬升 / 按压态", "#FFFFFF", "#343B49", 1),
    ("inverseSurface", "反向底（Snackbar）", "#1F2430", "#E6E9F2", 1),
    ("inverseOnSurface", "反向底上的文字", "#F3F4F8", "#10131A", 1),
    ("inversePrimary", "反向底上的主色", "#9FB0F0", "#3B4C9C", 1),
    ("scrim", "弹窗背后的遮罩", "#000000", "#000000", 1),
]

EXT = [
    ("glassTop", "卡片玻璃底色", "#FFFFFF", "#1A1F2B", 1),
    ("glassMid", "玻璃中段 —— 无落点", "#FFFFFF", "#1A1F2B", 0),
    ("glassBottom", "玻璃底段 —— 无落点", "#FFFFFF", "#1A1F2B", 0),
    ("glassRim", "玻璃卡顶部高光描边", "#FFFFFF", "#FFFFFF", 1),
    ("cardRim", "卡片 1dp 描边 / Hero 描边", "#E4E7EE", "#2A3140", 1),
    ("pillStart", "胶囊渐变起：依赖按钮 / Hero 兜底", "#3B4C9C", "#9FB0F0", 1),
    ("pillEnd", "胶囊渐变止（同上）", "#2B3A7A", "#2B3A7A", 1),
    ("navActiveStart", "底栏选中指示器底色", "#EEF0FA", "#2A3563", 1),
    ("navActiveEnd", "指示器渐变止 —— 无落点", "#EEF0FA", "#2A3563", 0),
    ("heroTrack", "Hero 卡内存条轨道", "#FFFFFF@0.22", "#FFFFFF@0.22", 1),
    ("heroBarStart", "Hero 卡内存条渐变起", "#8CF0C4", "#8CF0C4", 1),
    ("heroBarEnd", "Hero 卡内存条渐变止", "#2FBF87", "#2FBF87", 1),
    ("heroOnDim", "Hero 卡次要文字 / 标签", "#FFFFFF@0.70", "#FFFFFF@0.70", 1),
    ("heroOnSoft", "Hero 卡主文字 / 数值", "#FFFFFF@0.90", "#FFFFFF@0.90", 1),
    ("inkTitle", "卡片标题文字", "#1F2430", "#E6E9F2", 1),
    ("inkBody", "卡片正文 —— 无落点", "#6B7385", "#9AA3B5", 0),
    ("inkHint", "提示文字 —— 无落点", "#6B7385", "#9AA3B5", 0),
    ("night", "深色强调底 —— 无落点", "#1F2430", "#E6E9F2", 0),
    ("deep", "深色模式的背景压罩色", "#000000", "#000000", 1),
    ("hydro", "水色强调 —— 无落点", "#2FBF87", "#8CF0C4", 0),
    ("gold", "依赖按钮金边（写了才画）", "#F97066", "#FFB4A8", 1),
    ("goldInk", "金色文字 —— 无落点", "#F97066", "#FFB4A8", 0),
    ("topGlow", "页面顶部渐光色", "#000000@0.18", "#000000@0.18", 1),
    ("navSurface", "底栏面板底 —— 无落点", "#FFFFFF@0.96", "#1A1F2B@0.96", 0),
]

SHAPE = [
    ("cardRadius", 18, "卡片 McCard / 页面级容器"),
    ("controlRadius", 16, "按钮 / 输入框 / 列表行"),
    ("dialogRadius", 28, "弹窗"),
    ("heroRadius", 18, "Hero 卡"),
    ("navRadius", 18, "状态胶囊 / 分段滑轨"),
]

METRICS = [
    ("glassAlpha", "0.94", "有背景图时卡片不透明度", "0.70 – 1.0", 1, 0.94),
    ("navSurfaceAlpha", "0.96", "顶栏 / 底栏 / 折叠卡面板", "0.60 – 1.0", 1, 0.96),
    ("backdropScrimDark", "0.62", "深色模式背景压暗强度", "0 – 0.9", 1, 0.62),
    ("backdropTopGlowAlpha", "0.18", "顶部渐光强度", "0 – 0.4", 1, 0.18),
    ("heroScrim", "0", "Hero 插画压罩（0 = 不压）", "0 – 0.55", 1, 0.0),
    ("accentArtAlpha", "0.55", "启动条插画透明度 —— 无落点", "0 – 1", 0, 0.55),
    ("heroCardOffsetRatio", "0.0407", "Hero 卡上浮比例 —— 无落点", "0 – 0.1", 0, 0.041),
]

TYPO = [
    ("titleLetterSpacing", "0.4", "标题字距"),
    ("heroTitleSize", "21", "Hero 标题字号"),
    ("useSerifTitle", "false", "衬线标题"),
]

EXCLUDED = [
    ("终端配色", "终端有两套硬编码调色板，不跟主题走"),
    ("桌面小组件", "RemoteViews 读固定颜色资源，改了不变"),
    ("语义红绿", "停止固定珊瑚红、运行固定薄荷绿，防误操作"),
]

MANIFEST = [
    ("id", "唯一标识，小写连字符；内置 id 不可覆盖"),
    ("name", "列表里显示的名字，写中文"),
    ("version", "版本号，导出文件名也用它"),
    ("author", "作者，显示在主题详情里"),
    ("description", "一句话简介，作列表副标题"),
    ("supportsDark", "缺省 true；false 时深色也用 light"),
    ("preview", "包内缩略图路径，列表小图"),
    ("minAppVersionCode", "低于此版本不装，缺省 0"),
    ("tokens", "令牌文件名，缺省 tokens.json"),
]

SLOTS = [
    ("background", "整屏背景", "1500×3200 竖图，中段中等明度",
     "铺满整屏。卡片 / 顶栏 / 底栏半透明处才透得出来。"),
    ("hero", "状态卡插画", "1254×648，左深右亮好压白字",
     "概览页服务器状态卡底图；scale=overflow 可探出卡片。"),
    ("accent", "启动条插画", "1188×159 ≈ 7.5:1 横条",
     "「启动服务器」长条按钮底图，仅未运行状态出现。"),
]


# ── 工具 ───────────────────────────────────────────────────────
def hx(c):
    if "@" in c:
        a, b = c.split("@")
        return a, float(b)
    return c, 1.0


def swatch(b, x, y, size, color):
    """y 是行基线，色块以基线为垂直中心。"""
    c, a = hx(color)
    top = y - size / 2 - 3
    if a < 1.0:
        b.rect(x, top, size, size, "#FFFFFF", rx=5)
        half = size / 2
        b.rect(x, top, half, half, "#E4E8F0", rx=0)
        b.rect(x + half, top + half, half, half, "#E4E8F0", rx=0)
        b.rect(x, top, size, size, c, rx=5, opacity=a)
    else:
        b.rect(x, top, size, size, c, rx=5)
    b.rect(x, top, size, size, c, rx=5, stroke="#D5DBE6", sw=1, opacity=0.0)


def section(b, y, title, note, count=None):
    b.rect(M, y + 4, 4, 22, PRIMARY, rx=2)
    b.text(M + 16, y + 24, title, 21, INK, bold=True)
    tw = sum(21 if ord(ch) > 127 else 11.6 for ch in title)
    b.text(M + 16 + tw + 16, y + 23, note, 13, MUTED)
    if count:
        cw = len(count) * 7.6 + 24
        b.rect(M + CW - cw, y + 6, cw, 26, "#EDF0F7", rx=13)
        b.text(M + CW - cw / 2, y + 24, count, 12, PRIMARY, bold=True, anchor="middle")
    return y + 44


def card(b, y, h):
    b.rect(M, y, CW, h, CARD, rx=16, stroke=RIM, sw=1)


def pill(b, x, y, live):
    label = "生效" if live else "预留"
    b.rect(x, y - 13, 40, 20, OK_BG if live else PEND_BG, rx=10)
    b.text(x + 20, y + 1, label, 11, OK if live else PEND, bold=True, anchor="middle")


def token_row(b, x, y, key, desc, light, dark, live):
    """一行：浅色块 / 深色块 / 键名 / 状态 / 落点 / 默认值（右对齐两列）。"""
    swatch(b, x, y, 20, light)
    swatch(b, x + 26, y, 20, dark)
    b.text(x + 56, y + 5, key, 13, INK, mono=True, bold=True)
    pill(b, x + 250, y + 3, live)
    b.text(x + 300, y + 5, desc, 12.3, BODY if live else FAINT)
    b.text(x + IN_W - 2, y + 5, hx(dark)[0].upper(), 11.5, MUTED, mono=True, anchor="end")
    b.text(x + IN_W - 96, y + 5, hx(light)[0].upper(), 11.5, MUTED, mono=True, anchor="end")


def cw_of(ch, size):
    return size if ord(ch) > 127 else size * 0.56


def wrap(text, max_w, size):
    """按像素宽度折行（中英混排，粗略按字符宽度累积）。"""
    lines, cur, acc = [], "", 0.0
    for ch in text:
        c = cw_of(ch, size)
        if acc + c > max_w and cur:
            lines.append(cur)
            cur, acc = "", 0.0
        cur += ch
        acc += c
    if cur:
        lines.append(cur)
    return lines


def bar(b, x, y, w, ratio, color):
    b.rect(x, y, w, 6, "#E8EBF2", rx=3)
    b.rect(x, y, max(4, w * max(0.0, min(1.0, ratio))), 6, color, rx=3)


# ── 主绘制 ─────────────────────────────────────────────────────
def draw(b):
    # 头部
    b.rect(0, 0, W, 176, "#FFFFFF")
    b.rect(0, 175, W, 1, RIM)
    b.rect(M, 50, 5, 30, PRIMARY, rx=2)
    b.text(M + 20, 76, "主题包可替换项对照表", 34, INK, bold=True)
    b.text(M + 20, 106, "Minexa · .mctheme · schemaVersion 1", 14, MUTED)
    b.text(M + 20, 142, "先看「状态」列：生效 = 改了会变；预留 = 已解析但还没有落点，改了不显示。", 13.5, BODY)

    # 右上三步
    steps = ["① 解包 templates/mist-neutral", "② 改 tokens.json、换 assets 里的图",
             "③ themetool pack → validate"]
    for i, t in enumerate(steps):
        cy = 48 + i * 22
        b.circle(W - M - 6, cy - 4, 3, PRIMARY)
        b.text(W - M - 18, cy, t, 12, BODY, anchor="end")
    b.text(W - M - 6, 148, "tokens 缺键会回退到 App 默认色，不会报错", 11.5, FAINT, anchor="end")

    y = 206

    # §1 图片
    y = section(b, y, "① manifest.json 与图片资产", "包的入口文件；图放进包内，用相对路径指过去", "9 字段 + 3 槽")
    ch = 330
    card(b, y, ch)
    b.text(M + 18, y + 24, "manifest.json", 12, PRIMARY, bold=True, mono=True)
    b.text(M + 118, y + 24, "除 id / name 外都有缺省值；缺字段最多是「不显示」，不会让包失效", 11.5, FAINT)
    cw3 = (CW - 36) // 3
    for i, (k, desc) in enumerate(MANIFEST):
        col, row = i // 3, i % 3
        x = M + 18 + col * cw3
        ry = y + 54 + row * 32
        b.text(x, ry, k, 12.2, INK, mono=True, bold=True)
        b.text(x + len(k) * 7.4 + 12, ry, desc, 11.3, BODY)
    b.line(M + 18, y + 156, M + CW - 18, y + 156, "#EDEFF4", 1)
    b.text(M + 18, y + 180, "图片资产 assets", 12, PRIMARY, bold=True, mono=True)
    b.text(M + 146, y + 180, "三张图都可缺省：缺 background 就没有整屏背景，缺 hero / accent 就退回纯色渐变", 11.5, FAINT)
    cw3 = (CW - 36 - 40) // 3
    for i, (k, name, size, note) in enumerate(SLOTS):
        x = M + 18 + i * (cw3 + 20)
        b.rect(x, y + 196, 40, 40, "#DDE6FA", rx=9)
        b.text(x + 9, y + 221, "IMG", 11, PRIMARY, bold=True, mono=True)
        b.text(x + 50, y + 210, name, 14, INK, bold=True)
        b.text(x + 50, y + 229, f"assets.{k}", 11.2, PRIMARY, mono=True)
        b.text(x, y + 258, size, 11.8, BODY)
        for j, ln in enumerate(wrap(note, cw3 - 6, 11.3)):
            b.text(x, y + 278 + j * 18, ln, 11.3, MUTED)
    b.text(M + 18, y + ch - 12,
           "assetsMeta.<槽位>.scale = \"overflow\" 时立绘可探出卡片 / 按钮；默认 crop 裁在框内。",
           11.5, FAINT)
    y += ch + 24

    # §2 角色色
    rows = (len(COLORS) + 1) // 2
    y = section(b, y, "② 角色色 color.light / color.dark",
                "M3 角色色；App 里 700+ 处颜色引用最终都落到这 32 个", "32 × 2 份")
    ch = rows * ROW_H + 68
    card(b, y, ch)
    # 图例
    swatch(b, COL1_X, y + 26, 14, COLORS[0][2])
    b.text(COL1_X + 20, y + 30, "浅色默认值", 11, FAINT)
    swatch(b, COL1_X + 96, y + 26, 14, COLORS[0][3])
    b.text(COL1_X + 116, y + 30, "深色默认值", 11, FAINT)
    b.text(COL1_X + IN_W - 2, y + 30, "右侧两列 hex = 默认 light / dark", 11, FAINT, anchor="end")
    for i, row in enumerate(COLORS):
        cx = COL1_X + (COL2_X - COL1_X) * (i // rows)
        ry = y + 52 + (i % rows) * ROW_H
        token_row(b, cx, ry, *row)
    b.line(COL1_X, y + ch - 32, COL1_X + IN_W, y + ch - 32, "#EDEFF4", 1)
    b.text(COL1_X, y + ch - 12,
           '写法："color": { "light": { "primary": "#3B4C9C" }, "dark": { … } } —— 只写要改的键，'
           '缺键回退默认；键名写错会被静默忽略，validate 也不报。', 11.5, FAINT)
    y += ch + 24

    # §3 ext
    rows = (len(EXT) + 1) // 2
    y = section(b, y, "③ 扩展令牌 color.ext", "M3 没有、这个 App 自己加的：玻璃卡 / 胶囊 / Hero / 描边",
                "24 × 2 份")
    ch = rows * ROW_H + 56
    card(b, y, ch)
    for i, row in enumerate(EXT):
        cx = COL1_X + (COL2_X - COL1_X) * (i // rows)
        ry = y + 34 + (i % rows) * ROW_H
        token_row(b, cx, ry, *row)
    b.line(COL1_X, y + ch - 32, COL1_X + IN_W, y + ch - 32, "#EDEFF4", 1)
    b.text(COL1_X, y + ch - 12,
           '写法："ext": { "light": { "pillStart": "#3B4C9C" }, "dark": { … } }'
           '  —— 标「预留」的键能写进去，但界面上没有落点。', 11.5, FAINT)
    y += ch + 24

    # §4 圆角
    y = section(b, y, "④ 圆角 shape", "5 档，同一层级只用一档", "5 项")
    ch = 168
    card(b, y, ch)
    cw = (CW - 36 - 4 * 16) // 5
    for i, (k, v, note) in enumerate(SHAPE):
        x = M + 18 + i * (cw + 16)
        b.rect(x + (cw - 104) / 2, y + 22, 104, 52, "#EEF2FB", rx=min(v, 26),
               stroke="#C7D2EC", sw=1.5)
        b.text(x, y + 102, k, 12.5, INK, mono=True, bold=True)
        b.text(x + cw - 8, y + 102, f"{v:g} dp", 12.5, PRIMARY, bold=True, mono=True, anchor="end")
        b.text(x, y + 126, note, 11.2, MUTED)
    b.text(M + 18, y + ch - 16, "行内紧凑控件与列表行取 controlRadius − 2，跟着它一起变。", 11.5, FAINT)
    y += ch + 24

    # §5 metrics
    mrows = 4
    y = section(b, y, "⑤ 数值 metrics", "0–1 的强度值，写小数不写百分号", "7 项")
    ch = mrows * 72 + 26
    card(b, y, ch)
    for i, (k, val, note, rng, live, ratio) in enumerate(METRICS):
        cx = COL1_X + (COL2_X - COL1_X) * (i // mrows)
        ry = y + 36 + (i % mrows) * 72
        b.text(cx, ry, k, 13, INK, mono=True, bold=True)
        pill(b, cx + len(k) * 7.9 + 14, ry + 2, live)
        b.text(cx + IN_W - 8, ry, val, 13, PRIMARY, mono=True, bold=True, anchor="end")
        b.rect(cx, ry + 9, IN_W - 12, 5, "#E8EBF2", rx=2)
        b.rect(cx, ry + 9, max(4, (IN_W - 12) * max(0.0, min(1.0, ratio))), 5,
               OK if live else "#C8CEDA", rx=2, opacity=0.75)
        b.text(cx, ry + 44, note, 11.8, BODY if live else FAINT)
        b.text(cx + IN_W - 8, ry + 44, f"建议 {rng}", 11, FAINT, anchor="end")
    y += ch + 24

    # §6 typography
    y = section(b, y, "⑥ 排版 typography", "三项都还没接，改了不会变", "3 项")
    ch = 132
    card(b, y, ch)
    cw3 = (CW - 36) // 3
    for i, (k, v, note) in enumerate(TYPO):
        x = M + 18 + i * cw3
        b.text(x, y + 32, k, 12.5, INK, mono=True, bold=True)
        b.text(x, y + 62, v, 21, FAINT, mono=True, bold=True)
        b.text(x, y + 88, note, 11.5, FAINT)
    y += ch + 24

    # §7 管不到
    y = section(b, y, "⑦ 主题管不到的三处", "别在这上面反复试", "3 处")
    ch = 116
    card(b, y, ch)
    cw3 = (CW - 36) // 3
    for i, (k, v) in enumerate(EXCLUDED):
        x = M + 18 + i * cw3
        b.circle(x + 4, y + 34, 4, "#D3D9E5")
        b.text(x + 17, y + 39, k, 14, INK, bold=True)
        for j, ln in enumerate(wrap(v, cw3 - 10, 11.5)):
            b.text(x, y + 68 + j * 19, ln, 11.5, MUTED)
    y += ch + 24

    # §8 命令
    y = section(b, y, "⑧ 打包与校验", "两个都跑；validate 不过 App 里也装不上", None)
    ch = 118
    b.rect(M, y, CW, ch, "#1E2432", rx=14)
    for i, c in enumerate([
        "python tools/themetool.py pack <主题目录> -o mytheme.mctheme",
        "python tools/themetool.py validate mytheme.mctheme",
    ]):
        b.text(M + 24, y + 42 + i * 30, "$", 13, "#6E7C93", mono=True)
        b.text(M + 46, y + 42 + i * 30, c, 13, "#D8E2F5", mono=True)
    b.text(M + CW - 24, y + 42, "包内必须有 manifest.json + tokens.json", 11.5, "#7C89A0", anchor="end")
    b.text(M + CW - 24, y + 72, "缺 background 只是没背景；缺 tokens 直接判非法", 11.5, "#7C89A0", anchor="end")
    return y + ch + 40


def main():
    b = Svg()
    H = draw(b)
    for name, backend in (("theme-spec.svg", Svg()), ("theme-spec.png", Png(H))):
        backend.rect(0, 0, W, H, BG)
        draw(backend)
        backend.save(os.path.join(OUT_DIR, name), H)
        print("wrote", name, W, "x", int(H))


if __name__ == "__main__":
    main()
