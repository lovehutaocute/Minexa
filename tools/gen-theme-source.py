#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「主题素材源」：把每个可换肤组件按**真实 dp 尺寸**单独导出成 SVG / PNG。

用途（配合"生图模型重绘 → 矢量化 → 做主题"的流程）：
  - 每个 SVG 的 viewBox 就是 dp 尺寸（如 `0 0 328 46`），贴回界面不会变形；
  - PNG 是 3 倍图、**透明底**，可直接丢给生图模型；
  - 圆角、内间距、比例都来自 App 实际代码，不含阴影、不含文字，方便矢量化。

用法：
    python tools/gen-theme-source.py
输出：
    docs/theme-source/*.svg / *.png
    docs/theme-source/contact-sheet.png / .svg
"""

from __future__ import annotations

import os

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "docs", "theme-source")

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
MONO_F = "C:/Windows/Fonts/consola.ttf"

# 默认主题色（= tokens 缺省值）
MINT = "#2FBF87"
INDIGO = "#3B4C9C"
INDIGO_DARK = "#2B3A7A"
INDIGO_SOFT = "#EEF0FA"
MINT_SOFT = "#E7F8F0"
CORAL = "#F97066"
CORAL_SOFT = "#FDEEEC"
CARD = "#FFFFFF"
INK = "#1F2430"
MUTED = "#6B7385"
LINE = "#E4E7EE"
FIELD = "#F0F1F5"
GOLD = "#C9A65A"


# ── 两个后端（坐标一律用 dp，缩放由 s 决定）────────────────────
class Svg:
    def __init__(self, w, h, scale=3):
        self.parts = []
        self.w, self.h, self.scale = w, h, scale

    def rect(self, x, y, w, h, fill, rx=0, stroke=None, sw=0, opacity=1.0):
        fill = "none" if fill is None else fill
        s = f'<rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="{h:.2f}" rx="{rx:.2f}" fill="{fill}"'
        if opacity != 1.0:
            s += f' fill-opacity="{opacity}"'
        if stroke:
            s += f' stroke="{stroke}" stroke-width="{sw}"'
        self.parts.append(s + "/>")

    def line(self, x1, y1, x2, y2, color, w=1, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}" '
                          f'stroke="{color}" stroke-width="{w}" stroke-linecap="round"{d}/>')

    def circle(self, cx, cy, r, fill, opacity=1.0):
        o = f' fill-opacity="{opacity}"' if opacity != 1.0 else ""
        self.parts.append(f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="{r:.2f}" fill="{fill}"{o}/>')

    def poly(self, pts, fill):
        p = " ".join(f"{x:.2f},{y:.2f}" for x, y in pts)
        self.parts.append(f'<polygon points="{p}" fill="{fill}"/>')

    def grad(self, x, y, w, h, rx, c1, c2, gid="g0"):
        self.parts.insert(0, f'<linearGradient id="{gid}" x1="0%" y1="0%" x2="100%" y2="0%">'
                             f'<stop offset="0" stop-color="{c1}"/>'
                             f'<stop offset="1" stop-color="{c2}"/></linearGradient>')
        self.parts.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="{h:.2f}" '
                          f'rx="{rx:.2f}" fill="url(#{gid})"/>')

    def text(self, x, y, s, size, color, bold=False, mono=False):
        extra = ' font-weight="700"' if bold else ""
        fam = "Consolas, Menlo, monospace" if mono else "Microsoft YaHei, PingFang SC, sans-serif"
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        self.parts.append(f'<text x="{x:.1f}" y="{y - size * 0.78:.1f}" font-family="{fam}" '
                          f'font-size="{size}" fill="{color}"{extra}>{s}</text>')

    def save(self, path):
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w * self.scale}" '
                f'height="{self.h * self.scale}" viewBox="0 0 {self.w:g} {self.h:g}">')
        grads = "".join(p for p in self.parts if p.startswith("<linearGradient"))
        rest = "".join(p for p in self.parts if not p.startswith("<linearGradient"))
        with open(path, "w", encoding="utf-8") as f:
            f.write(head + ("<defs>" + grads + "</defs>" if grads else "") + rest + "</svg>")


class Png:
    """透明底位图，1dp = `scale` 像素。"""

    def __init__(self, w, h, scale=3):
        from PIL import Image, ImageDraw

        self.scale = scale
        self.img = Image.new("RGBA", (round(w * scale), round(h * scale)), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)
        self._Image = Image

    def rect(self, x, y, w, h, fill, rx=0, stroke=None, sw=0, opacity=1.0):
        box = [x * self.scale, y * self.scale, (x + w) * self.scale, (y + h) * self.scale]
        f = None if fill is None else (*self._rgb(fill), round(255 * opacity))
        st = None if stroke is None else (*self._rgb(stroke), 255)
        self.d.rounded_rectangle(box, radius=rx * self.scale, fill=f, outline=st,
                                 width=max(1, round(sw * self.scale)) if sw else 0)

    def line(self, x1, y1, x2, y2, color, w=1, dash=None):
        self.d.line([x1 * self.scale, y1 * self.scale, x2 * self.scale, y2 * self.scale],
                    fill=(*self._rgb(color), 255), width=max(1, round(w * self.scale)))

    def circle(self, cx, cy, r, fill, opacity=1.0):
        r *= self.scale
        self.d.ellipse([cx * self.scale - r, cy * self.scale - r,
                        cx * self.scale + r, cy * self.scale + r],
                       fill=(*self._rgb(fill), round(255 * opacity)))

    def poly(self, pts, fill):
        self.d.polygon([(x * self.scale, y * self.scale) for x, y in pts],
                       fill=(*self._rgb(fill), 255))

    def grad(self, x, y, w, h, rx, c1, c2, gid=None):
        """横向渐变：逐列画线再上圆角遮罩。"""
        px_w, px_h = round(w * self.scale), round(h * self.scale)
        g = self._Image.new("RGBA", (max(1, px_w), max(1, px_h)))
        from PIL import ImageDraw

        gd = ImageDraw.Draw(g)
        a, b = self._rgb(c1), self._rgb(c2)
        for i in range(px_w):
            t = i / max(1, px_w - 1)
            gd.line([(i, 0), (i, px_h)],
                    fill=(round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t),
                          round(a[2] + (b[2] - a[2]) * t), 255))
        mask = self._Image.new("L", (max(1, px_w), max(1, px_h)), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, px_w, px_h],
                                               radius=round(rx * self.scale), fill=255)
        self.img.paste(g, (round(x * self.scale), round(y * self.scale)), mask)

    def text(self, x, y, s, size, color, bold=False, mono=False):
        from PIL import ImageFont

        key = (round(size), bold, mono)
        if not hasattr(self, "_fonts"):
            self._fonts = {}
        if key not in self._fonts:
            path = MONO_F if mono else (FONT_B if bold else FONT)
            self._fonts[key] = ImageFont.truetype(path, round(size))
        self.d.text((x, y - size * 0.78), s, font=self._fonts[key],
                    fill=(*self._rgb(color), 255), anchor="ls")

    @staticmethod
    def _rgb(c):
        return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))

    def save(self, path):
        self.img.save(path)


# ── dp 坐标下的绘制助手 ────────────────────────────────────────
class Pen:
    """把 (ox, oy, s) 折成 dp 坐标的画笔，painter 里直接写 dp 数字。"""

    def __init__(self, b, ox, oy, s):
        self.b, self.ox, self.oy, self.s = b, ox, oy, s

    def r(self, x, y, w, h, fill, rx=0, stroke=None, sw=0, opacity=1.0):
        self.b.rect(self.ox + x * self.s, self.oy + y * self.s, w * self.s, h * self.s,
                    fill, rx=rx * self.s, stroke=stroke, sw=sw * self.s, opacity=opacity)

    def ln(self, x1, y1, x2, y2, color, w=1):
        self.b.line(self.ox + x1 * self.s, self.oy + y1 * self.s,
                    self.ox + x2 * self.s, self.oy + y2 * self.s, color, w=w * self.s)

    def c(self, cx, cy, r, fill, opacity=1.0):
        self.b.circle(self.ox + cx * self.s, self.oy + cy * self.s, r * self.s, fill, opacity)

    def poly(self, pts, fill):
        self.b.poly([(self.ox + x * self.s, self.oy + y * self.s) for x, y in pts], fill)

    def grad(self, x, y, w, h, rx, c1, c2):
        self.b.grad(self.ox + x * self.s, self.oy + y * self.s, w * self.s, h * self.s,
                    rx * self.s, c1, c2)


# ── 组件定义：name, 中文名, 宽, 高, 令牌, painter ─────────────
def c_start_button(p):
    p.r(0, 0, 328, 46, MINT, rx=16)
    p.poly([(148, 15), (148, 31), (161, 23)], CARD)


def c_stop_buttons(p):
    p.r(0, 0, 159, 46, CARD, rx=16, stroke=CORAL, sw=1.5)
    p.r(169, 0, 159, 46, CORAL, rx=16)


def c_env_button(p):
    p.grad(0, 0, 116, 36, 14, INDIGO, INDIGO_DARK)
    p.r(0, 0, 116, 36, None, rx=14, stroke=GOLD, sw=1)
    p.c(17, 14, 5, CARD)
    p.c(17, 14, 2.4, INDIGO_DARK)
    p.r(8, 20, 11, 4, CARD, rx=2)


def c_row_button(p):
    p.r(0, 0, 328, 36, INDIGO_SOFT, rx=14)


def c_buttons_pair(p):
    p.r(0, 0, 156, 44, INDIGO, rx=16)
    p.r(172, 0, 156, 44, CARD, rx=16, stroke=LINE, sw=1.5)


def c_list_card(p):
    p.r(0, 0, 328, 96, CARD, rx=18, stroke=LINE, sw=1)
    p.ln(16, 44, 312, 44, LINE, 1)
    p.ln(288, 20, 296, 28, MUTED, 1.6)
    p.ln(296, 28, 304, 20, MUTED, 1.6)


def c_address_card(p):
    p.r(0, 0, 328, 96, CARD, rx=18, stroke=LINE, sw=1)
    p.c(258, 70, 12, INDIGO_SOFT)
    p.ln(252, 66, 262, 66, INDIGO, 1.4)
    p.ln(262, 66, 262, 76, INDIGO, 1.4)
    p.r(284, 64, 12, 14, None, rx=3, stroke=INDIGO, sw=1.2)
    p.r(280, 60, 12, 14, CARD, rx=3, stroke=INDIGO, sw=1.2)


def c_hero_card(p):
    p.grad(0, 0, 328, 112, 18, INDIGO, "#233063")
    p.r(0, 0, 328, 112, None, rx=18, stroke=LINE, sw=1)
    p.c(20, 22, 3.5, MINT)
    p.r(16, 76, 180, 7, CARD, rx=4, opacity=0.24)
    p.r(16, 76, 122, 7, MINT, rx=4)


def c_setting_row(p):
    p.r(0, 0, 328, 56, CARD)
    p.r(16, 18, 96, 10, INK, rx=4)      # 标题占位
    p.r(16, 34, 130, 7, LINE, rx=3)     # 说明占位
    p.r(300, 24, 12, 8, LINE, rx=3)     # 右侧值 / 箭头位
    p.ln(16, 55, 312, 55, LINE, 1)


def c_input_field(p):
    p.r(0, 0, 328, 56, FIELD, rx=14)
    p.r(0, 0, 328, 56, None, rx=14, stroke=INDIGO, sw=1.4)
    p.r(16, 26, 1.5, 18, INDIGO, rx=1)


def c_segment(p):
    p.r(0, 0, 200, 36, FIELD, rx=18)
    p.r(3, 3, 97, 30, CARD, rx=15)


def c_switch_row(p):
    p.r(0, 0, 328, 48, CARD, rx=16, stroke=LINE, sw=1)
    p.r(258, 12, 50, 24, MINT, rx=12)
    p.c(294, 24, 9.5, CARD)


def c_progress(p):
    p.r(0, 8, 328, 10, FIELD, rx=5)
    p.r(0, 8, 203, 10, INDIGO, rx=5)


def c_banner(p):
    p.r(0, 0, 328, 44, INDIGO_SOFT, rx=12)
    p.r(0, 10, 3, 24, INDIGO, rx=2)


def c_status_chip(p):
    p.r(0, 0, 76, 26, MINT_SOFT, rx=13)
    p.c(15, 13, 3.5, MINT)


def c_header(p):
    p.r(0, 0, 360, 72, CARD)
    p.r(0, 71, 360, 1, LINE)
    p.r(16, 20, 60, 8, LINE, rx=4)
    p.r(16, 36, 120, 14, INK, rx=4)
    p.r(308, 24, 16, 16, None, rx=4, stroke=MUTED, sw=1.2)
    p.r(330, 24, 16, 16, None, rx=4, stroke=MUTED, sw=1.2)


def c_navbar(p):
    p.r(0, 0, 360, 80, CARD)
    p.r(0, 0, 360, 1, LINE)
    for i, cx in enumerate((60, 180, 300)):
        if i == 0:
            p.r(cx - 28, 10, 56, 28, INDIGO_SOFT, rx=14)
        p.r(cx - 8, 18, 16, 13, None, rx=3, stroke=INDIGO if i == 0 else MUTED, sw=1.4)
        p.r(cx - 12, 54, 24, 6, INDIGO if i == 0 else MUTED, rx=3)


def c_dialog(p):
    p.r(0, 0, 300, 176, CARD, rx=28)
    p.r(24, 32, 140, 14, INK, rx=4)
    p.r(24, 60, 240, 8, LINE, rx=4)
    p.r(24, 76, 200, 8, LINE, rx=4)
    p.r(196, 132, 36, 8, LINE, rx=4)
    p.r(244, 132, 36, 8, CORAL, rx=4)


def c_terminal(p):
    p.r(0, 0, 328, 200, "#12161F", rx=14)
    for i, (w, col) in enumerate([(190, "#7C879B"), (120, "#8BE9A8"), (200, "#E0E4EC"),
                                  (150, "#7C879B"), (175, "#7C879B")]):
        p.r(16, 22 + i * 30, w, 8, col, rx=4)


def c_widget(p):
    p.r(0, 0, 320, 80, CARD, rx=18)
    p.c(18, 20, 4, MINT)
    p.r(12, 34, 110, 12, INK, rx=4)
    p.r(12, 56, 140, 7, LINE, rx=3)
    p.r(246, 16, 58, 24, MINT_SOFT, rx=12)


COMPONENTS = [
    ("01-start-button", "启动大按钮", 328, 46, ["secondary", "controlRadius 16"], c_start_button),
    ("02-stop-buttons", "停止 / 强制停止", 328, 46, ["tertiary", "controlRadius 16"], c_stop_buttons),
    ("03-env-button", "功能按钮（依赖与环境管理）", 116, 36,
     ["pillStart", "pillEnd", "gold", "controlRadius 14"], c_env_button),
    ("04-row-button", "长条按钮", 328, 36, ["primaryContainer", "controlRadius 14"], c_row_button),
    ("05-buttons-pair", "主按钮 / 次按钮", 328, 44, ["primary", "outline"], c_buttons_pair),
    ("06-list-card", "列表卡片", 328, 96, ["glassTop", "cardRim", "cardRadius 18"], c_list_card),
    ("07-address-card", "地址卡片", 328, 96, ["glassTop", "cardRim", "primary"], c_address_card),
    ("08-hero-card", "Hero 状态卡", 328, 112,
     ["assets.hero", "pillStart", "heroTrack", "heroBarStart", "heroRadius 18"], c_hero_card),
    ("09-setting-row", "设置行", 328, 56, ["inkTitle", "outline"], c_setting_row),
    ("10-input-field", "输入框", 328, 56, ["surfaceVariant", "outline", "controlRadius 14"],
     c_input_field),
    ("11-segment", "分段控件", 200, 36, ["surface", "primary", "navRadius 18"], c_segment),
    ("12-switch-row", "开关行", 328, 48, ["secondary", "controlRadius 16"], c_switch_row),
    ("13-progress", "进度条", 328, 24, ["surfaceVariant", "primary"], c_progress),
    ("14-banner", "提示条", 328, 44, ["primaryContainer", "primary"], c_banner),
    ("15-status-chip", "状态胶囊", 76, 26, ["secondaryContainer", "navRadius 13"], c_status_chip),
    ("16-header", "顶栏", 360, 72, ["surface", "onBackground"], c_header),
    ("17-navbar", "底栏", 360, 80, ["surface", "navActiveStart"], c_navbar),
    ("18-dialog", "弹窗", 300, 176, ["surface", "tertiary", "dialogRadius 28"], c_dialog),
    ("19-terminal", "终端日志（不跟随主题）", 328, 200, [], c_terminal),
    ("20-widget", "桌面小组件（不跟随主题）", 320, 80, [], c_widget),
]


# ── 输出 ──────────────────────────────────────────────────────
def export_all():
    svg_dir = os.path.join(OUT, "svg")
    png_dir = os.path.join(OUT, "png")
    os.makedirs(svg_dir, exist_ok=True)
    os.makedirs(png_dir, exist_ok=True)
    for name, label, w, h, tokens, painter in COMPONENTS:
        svg = Svg(w, h, scale=3)
        painter(Pen(svg, 0, 0, 1))
        svg.save(os.path.join(svg_dir, name + ".svg"))

        png = Png(w, h, scale=3)
        painter(Pen(png, 0, 0, 3))
        png.save(os.path.join(png_dir, name + ".png"))
    print(f"exported {len(COMPONENTS) * 2} files -> svg/ + png/")


def manifest_csv():
    import csv

    path = os.path.join(OUT, "manifest.csv")
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["file", "name_zh", "width_dp", "height_dp", "png_px_3x", "tokens"])
        for name, label, wd, ht, tokens, _ in COMPONENTS:
            w.writerow([name, label, wd, ht, f"{wd * 3}x{ht * 3}", " ".join(tokens)])
    print("manifest.csv")


def contact_sheet():
    """白底对照表：左侧名称 + dp 尺寸 + 令牌，右侧按 1:1 比例（×2.2）画的组件。"""
    S = 2.6
    W = 1680
    M = 56
    ROW_GAP = 26
    LEFT = 560                     # 左栏文字区宽

    rows = []
    y = 208
    for name, label, w, h, tokens, painter in COMPONENTS:
        rows.append((y, name, label, w, h, tokens, painter))
        y += h * S + ROW_GAP + 34
    H = int(y + 40)

    svg = Svg(W, H, scale=1)
    png = Png(W, H, scale=1)

    for b in (svg, png):
        b.rect(0, 0, W, H, "#EDF0F6")
        b.rect(0, 0, W, 168, "#FFFFFF")
        b.rect(0, 167, W, 1, LINE)
        b.rect(M, 46, 5, 30, INDIGO, rx=2)

    Pen(svg, 0, 0, 1)
    for b, is_svg in ((svg, True), (png, False)):
        # 标题用同一套字体位置：SVG 单独写字，PNG 用 PIL
        if is_svg:
            _title_svg(svg)
        else:
            _title_png(png)

    for ry, name, label, w, h, tokens, painter in rows:
        gx = M + LEFT
        for b, is_svg in ((svg, True), (png, False)):
            b.rect(M, ry - 20, W - M * 2, h * S + 40, "#F7F9FC", rx=14, stroke="#DCE2EC", sw=1)
            # 画布边界：固定 8px 虚线，不受组件缩放着色影响
            bx, by = gx - 8, ry - 8
            for i in range(0, int(w * S) + 16, 10):
                b.rect(bx + i, by, 5, 1, "#BFC7D8")
                b.rect(bx + i, by + h * S + 16, 5, 1, "#BFC7D8")
            for i in range(0, int(h * S) + 16, 10):
                b.rect(bx, by + i, 1, 5, "#BFC7D8")
                b.rect(bx + w * S + 16, by + i, 1, 5, "#BFC7D8")
        painter(Pen(svg, gx, ry, S))
        painter(Pen(png, gx, ry, S))

        for b, is_svg in ((svg, True), (png, False)):
            if is_svg:
                b.text(M + 18, ry + 4, label, 17, "#1C2333", bold=True)
                b.text(M + 18, ry + 28, name, 11.5, "#3B4C9C", mono=True)
                b.text(M + 18, ry + 50, f"{w:g} × {h:g} dp", 12.5, "#3D4759", mono=True)
                tx = M + 18
                for t in tokens:
                    tw = len(t) * 6.0 + 18
                    if tx + tw > M + LEFT - 24:
                        b.text(tx + 4, ry + 74, "…", 11, "#9AA3B3")
                        break
                    b.rect(tx, ry + 60, tw, 20, "#EEF2FB", rx=10)
                    b.text(tx + 9, ry + 74, t, 10, "#3B4C9C", mono=True)
                    tx += tw + 6
            else:
                b.text(M + 18, ry + 4, label, 17, "#1C2333", bold=True)
                b.text(M + 18, ry + 28, name, 11.5, "#3B4C9C", mono=True)
                b.text(M + 18, ry + 50, f"{w:g} × {h:g} dp", 12.5, "#3D4759", mono=True)
                tx = M + 18
                for t in tokens:
                    tw = len(t) * 6.0 + 18
                    if tx + tw > M + LEFT - 24:
                        b.text(tx + 4, ry + 74, "…", 11, "#9AA3B3")
                        break
                    b.rect(tx, ry + 60, tw, 20, "#EEF2FB", rx=10)
                    b.text(tx + 9, ry + 74, t, 10, "#3B4C9C", mono=True)
                    tx += tw + 6

    svg.save(os.path.join(OUT, "contact-sheet.svg"))
    png.save(os.path.join(OUT, "contact-sheet.png"))
    print("contact sheet", W, "x", H)


def _title_svg(b):
    b.text(76, 72, "主题素材源 · 组件按真实 dp 尺寸导出", 26, "#1C2333", bold=True)
    b.text(76, 104, "每个 SVG 的 viewBox 就是 dp 尺寸，PNG 是 3 倍透明底图。"
                    "全部无阴影、无文字，可直接重绘 / 矢量化。", 13, "#737E92")
    b.text(76, 128, "虚线框 = 画布边界；深色组件（Hero / 终端）的边框仅用于标识范围，导出文件里没有。",
           12, "#9AA3B3")


def _title_png(b):
    from PIL import ImageDraw, ImageFont

    d = b.d
    d.text((76, 46), "主题素材源 · 组件按真实 dp 尺寸导出", font=ImageFont.truetype(FONT_B, 26),
           fill="#1C2333")
    d.text((76, 90), "每个 SVG 的 viewBox 就是 dp 尺寸，PNG 是 3 倍透明底图。"
                     "全部无阴影、无文字，可直接重绘 / 矢量化。",
           font=ImageFont.truetype(FONT, 13), fill="#737E92")
    d.text((76, 114), "虚线框 = 画布边界；深色组件（Hero / 终端）的边框仅用于标识范围，导出文件里没有。",
           font=ImageFont.truetype(FONT, 12), fill="#9AA3B3")


def pack():
    """打成 zip：svg/ 与 png/ 分目录，方便生图工具批量选 PNG。"""
    import zipfile

    dst = os.path.join(os.path.dirname(OUT), "minexa-theme-source.zip")
    root = "minexa-theme-source"
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for sub in ("svg", "png"):
            for f in sorted(os.listdir(os.path.join(OUT, sub))):
                z.write(os.path.join(OUT, sub, f), f"{root}/{sub}/{f}")
        for f in ("README.md", "manifest.csv", "contact-sheet.png", "contact-sheet.svg"):
            fp = os.path.join(OUT, f)
            if os.path.exists(fp):
                z.write(fp, f"{root}/{f}")
    size = os.path.getsize(dst)
    print(f"packed -> {dst} ({size / 1024:.0f} KB)")


if __name__ == "__main__":
    export_all()
    manifest_csv()
    contact_sheet()
    pack()
