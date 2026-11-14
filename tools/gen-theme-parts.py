#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「主题组件图鉴」：把 App 里能换肤的零件逐个画出来，标注各归哪个令牌。

做主题时对着这张图找：「我要改的是这个绿按钮」→ 下面写着 secondary / controlRadius。

用法：
    python tools/gen-theme-parts.py
输出：
    docs/theme-parts.svg
    docs/theme-parts.png
"""

from __future__ import annotations

import os

W = 1680
M = 56
CW = W - M * 2
GAP = 20
CELL_W = (CW - GAP * 2) // 3    # 509
CELL_H = 282
AREA_H = 148                    # 图形区高度

BG = "#F5F6FA"
INK = "#1C2333"
BODY = "#3D4759"
MUTED = "#737E92"
FAINT = "#9AA3B3"
PRIMARY = "#3B4C9C"
RIM = "#E3E7EF"

D_MINT = "#2FBF87"
D_INDIGO = "#3B4C9C"
D_INDIGO_DARK = "#2B3A7A"
D_CORAL = "#F97066"
D_BG = "#F3F4F8"
D_CARD = "#FFFFFF"
D_INK = "#1F2430"
D_MUTED = "#6B7385"
D_LINE = "#E4E7EE"
D_FIELD = "#F0F1F5"
D_INDIGO_SOFT = "#EEF0FA"
D_MINT_SOFT = "#E7F8F0"
D_CORAL_SOFT = "#FDEEEC"

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
MONO_F = "C:/Windows/Fonts/consola.ttf"
MONO_FB = "C:/Windows/Fonts/consolab.ttf"

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs")


# ── 后端 ───────────────────────────────────────────────────────
class Svg:
    def __init__(self):
        self.parts = []

    def rect(self, x, y, w, h, fill, rx=0, stroke=None, sw=0, opacity=1.0):
        fill = "none" if fill is None else fill
        s = f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" fill="{fill}"'
        if opacity != 1.0:
            s += f' fill-opacity="{opacity}"'
        if stroke:
            s += f' stroke="{stroke}" stroke-width="{sw}"'
        self.parts.append(s + "/>")

    def line(self, x1, y1, x2, y2, color, w=2):
        self.parts.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
                          f'stroke="{color}" stroke-width="{w}"/>')

    def circle(self, cx, cy, r, fill, opacity=1.0):
        o = f' fill-opacity="{opacity}"' if opacity != 1.0 else ""
        self.parts.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{fill}"{o}/>')

    def poly(self, pts, fill):
        p = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        self.parts.append(f'<polygon points="{p}" fill="{fill}"/>')

    def text(self, x, y, s, size, color, bold=False, anchor="start", mono=False, opacity=1.0):
        a = {"start": "start", "middle": "middle", "end": "end"}[anchor]
        extra = ' font-weight="700"' if bold else ""
        if opacity != 1.0:
            extra += f' fill-opacity="{opacity}"'
        fam = "Consolas, Menlo, monospace" if mono else "Microsoft YaHei, PingFang SC, sans-serif"
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        self.parts.append(
            f'<text x="{x:.1f}" y="{y - size * 0.78:.1f}" font-family="{fam}" font-size="{size}" '
            f'fill="{color}" text-anchor="{a}"{extra}>{s}</text>'
        )

    def grad(self, x, y, w, h, rx, stops, horizontal=True):
        gid = f"g{len(self.parts)}"
        x2, y2 = ("100%", "0%") if horizontal else ("0%", "100%")
        body = "".join(f'<stop offset="{i / max(1, len(stops) - 1) * 100:.0f}%" stop-color="{c}"/>'
                       for i, c in enumerate(stops))
        self.parts.append(f'<linearGradient id="{gid}" x1="0%" y1="0%" x2="{x2}" y2="{y2}">'
                          f'{body}</linearGradient>')
        self.parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" '
                          f'rx="{rx}" fill="url(#{gid})"/>')

    def save(self, path, h):
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" '
                f'viewBox="0 0 {W} {h}">')
        body = "".join(p for p in self.parts if p.startswith("<linearGradient"))
        rest = "".join(p for p in self.parts if not p.startswith("<linearGradient"))
        with open(path, "w", encoding="utf-8") as f:
            f.write(head + "<defs>" + body + "</defs>" + rest + "</svg>")


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
            self.d.rounded_rectangle([x, y, x + w, y + h], radius=rx,
                                     fill=None if fill is None else fill,
                                     outline=stroke, width=max(1, int(round(sw))) if sw else 0)
        else:
            layer = self._Image.new("RGBA", (max(1, int(w)), max(1, int(h))), (0, 0, 0, 0))
            from PIL import ImageDraw

            dd = ImageDraw.Draw(layer)
            rgb = tuple(int(fill[i:i + 2], 16) for i in (1, 3, 5))
            dd.rounded_rectangle([0, 0, w, h], radius=rx, fill=(*rgb, int(255 * opacity)))
            self.img.paste(layer, (int(x), int(y)), layer)

    def line(self, x1, y1, x2, y2, color, w=2):
        self.d.line([(x1, y1), (x2, y2)], fill=color, width=max(1, int(round(w))))

    def circle(self, cx, cy, r, fill, opacity=1.0):
        self.d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill)

    def poly(self, pts, fill):
        self.d.polygon([(x, y) for x, y in pts], fill=fill)

    def text(self, x, y, s, size, color, bold=False, anchor="start", mono=False, opacity=1.0):
        f = self._font(size, bold, mono)
        a = {"start": "ls", "middle": "ms", "end": "rs"}[anchor]
        self.d.text((x, y - size * 0.78), s, font=f, fill=color, anchor=a)

    def grad(self, x, y, w, h, rx, stops, horizontal=True):
        c1 = tuple(int(stops[0][i:i + 2], 16) for i in (1, 3, 5))
        c2 = tuple(int(stops[-1][i:i + 2], 16) for i in (1, 3, 5))
        g = self._Image.new("RGB", (max(1, int(w)), max(1, int(h))))
        from PIL import ImageDraw

        gd = ImageDraw.Draw(g)
        n = int(w) if horizontal else int(h)
        for i in range(n):
            t = i / max(1, n - 1)
            col = tuple(int(c1[k] + (c2[k] - c1[k]) * t) for k in range(3))
            if horizontal:
                gd.line([(i, 0), (i, h)], fill=col)
            else:
                gd.line([(0, i), (w, i)], fill=col)
        mask = self._Image.new("L", (max(1, int(w)), max(1, int(h))), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, w, h], radius=rx, fill=255)
        self.img.paste(g, (int(x), int(y)), mask)

    def save(self, path, h):
        self.img.save(path)


# ── 令牌徽标 ───────────────────────────────────────────────────
def chip_w(name):
    return len(name) * 6.4 + 22


def chip(b, x, y, name, color=None):
    cw = chip_w(name)
    b.rect(x, y - 13, cw, 22, "#EEF2FB", rx=11)
    tx = x + 8
    if color:
        b.rect(x + 6, y - 6, 10, 10, color, rx=3, stroke="#B9C4D8", sw=1)
        tx = x + 21
    b.text(tx, y + 1, name, 11, PRIMARY, mono=True)
    return cw


def chip_row(b, x, y, w, items, max_lines=3):
    cx, cy, line = x, y, 0
    for name, color in items:
        cw = chip_w(name)
        if cx + cw > x + w and cx > x:
            line += 1
            if line >= max_lines:
                return cy
            cx, cy = x, cy + 27
        cw = chip(b, cx, cy, name, color)
        cx += cw + 6
    return cy


def chevron(b, x, y, color, size=5):
    b.line(x - size, y - size * 0.5, x, y + size * 0.6, color, 1.8)
    b.line(x, y + size * 0.6, x + size, y - size * 0.5, color, 1.8)


# ── 组件画家（在图形区内垂直居中）──────────────────────────────
def p_start_btn(b, x, y, w):
    bw, bh = 300, 52
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx + 1, by + 4, bw, bh, "#DDE2EA", rx=16)
    b.rect(bx, by, bw, bh, D_MINT, rx=16)
    b.poly([(bx + 98, by + 16), (bx + 98, by + 36), (bx + 114, by + 26)], "#FFFFFF")
    b.text(bx + 126, by + 33, "启动", 19, "#FFFFFF", bold=True)


def p_stop_btn(b, x, y, w):
    bw, bh, gap = 158, 52, 12
    bx = x + (w - (bw * 2 + gap)) / 2
    by = y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_CARD, rx=16, stroke=D_CORAL, sw=2)
    b.text(bx + bw / 2, by + 33, "强制停止", 16, D_CORAL, bold=True, anchor="middle")
    b.rect(bx + bw + gap, by, bw, bh, D_CORAL, rx=16)
    b.text(bx + bw + gap + bw / 2, by + 33, "停止", 16, "#FFFFFF", bold=True, anchor="middle")


def p_env_btn(b, x, y, w):
    bw, bh = 300, 48
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx + 1, by + 4, bw, bh, "#D8DFEA", rx=14)
    b.grad(bx, by, bw, bh, 14, [D_INDIGO, D_INDIGO_DARK])
    b.rect(bx, by, bw, bh, None, rx=14, stroke="#C9A65A", sw=1.5)
    # 简化扳手：一个开口的环 + 一段柄（纯几何，两后端渲染一致）
    b.circle(bx + 41, by + 17, 6.5, "#FFFFFF")
    b.circle(bx + 41, by + 17, 3.2, D_INDIGO_DARK)
    b.rect(bx + 28, by + 26, 12, 5, "#FFFFFF", rx=2)
    b.text(bx + 58, by + 31, "依赖与环境管理", 16, "#FFFFFF", bold=True)


def p_long_btn(b, x, y, w):
    bw, bh = 430, 48
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_INDIGO_SOFT, rx=14)
    b.text(bx + 20, by + 30, "切换服务器核心：", 14, D_INDIGO)
    b.text(bx + 154, by + 30, "server-b3d2485062c1", 14, D_INDIGO, mono=True, bold=True)


def p_pair_btn(b, x, y, w):
    bw, bh = 186, 48
    bx = x + (w - (bw * 2 + 14)) / 2
    by = y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_INDIGO, rx=16)
    b.text(bx + bw / 2, by + 31, "保存", 16, "#FFFFFF", bold=True, anchor="middle")
    b.rect(bx + bw + 14, by, bw, bh, D_CARD, rx=16, stroke=D_LINE, sw=2)
    b.text(bx + bw + 14 + bw / 2, by + 31, "取消", 16, D_MUTED, anchor="middle")


def p_layout_card(b, x, y, w):
    cw, ch = 400, 138
    cx, cy = x + (w - cw) / 2, y + (AREA_H - ch) / 2
    b.rect(cx + 1, cy + 4, cw, ch, "#E6E9F0", rx=18)
    b.rect(cx, cy, cw, ch, D_CARD, rx=18, stroke=D_LINE, sw=1)
    b.text(cx + 22, cy + 40, "已安装插件", 18, D_INK, bold=True)
    b.line(cx + 20, cy + 62, cx + cw - 20, cy + 62, D_LINE, 1)
    b.text(cx + 22, cy + 92, "暂无已安装插件", 14, D_MUTED)
    chevron(b, cx + cw - 32, cy + 32, D_MUTED, 6)


def p_addr_card(b, x, y, w):
    cw, ch = 400, 138
    cx, cy = x + (w - cw) / 2, y + (AREA_H - ch) / 2
    b.rect(cx + 1, cy + 4, cw, ch, "#E6E9F0", rx=18)
    b.rect(cx, cy, cw, ch, D_CARD, rx=18, stroke=D_LINE, sw=1)
    b.text(cx + 22, cy + 36, "服务器地址", 17, D_INK, bold=True)
    b.text(cx + 22, cy + 72, "局域网", 12, D_MUTED)
    b.text(cx + 22, cy + 100, "192.168.1.166:25565", 15.5, D_INK, bold=True, mono=True)
    b.circle(cx + cw - 88, cy + 96, 12, D_INDIGO_SOFT)
    b.line(cx + cw - 94, cy + 92, cx + cw - 83, cy + 92, D_INDIGO, 1.8)
    b.line(cx + cw - 83, cy + 92, cx + cw - 83, cy + 102, D_INDIGO, 1.8)
    b.rect(cx + cw - 56, cy + 90, 14, 16, None, rx=3, stroke=D_INDIGO, sw=1.6)
    b.rect(cx + cw - 60, cy + 86, 14, 16, D_CARD, rx=3, stroke=D_INDIGO, sw=1.6)


def p_hero_card(b, x, y, w, wide=False):
    cw = 660 if wide else 440
    ch = 140
    cx, cy = x + (w - cw) / 2, y + (AREA_H - ch) / 2
    b.rect(cx + 1, cy + 4, cw, ch, "#D8DFEC", rx=18)
    b.grad(cx, cy, cw, ch, 18, [D_INDIGO, "#233063"])
    b.rect(cx, cy, cw, ch, None, rx=18, stroke="#CFD8EA", sw=1)
    b.circle(cx + 27, cy + 28, 4, D_MINT)
    b.text(cx + 38, cy + 33, "运行中", 12, "#FFFFFF", bold=True)
    b.text(cx + 22, cy + 62, "我的生存服", 20, "#FFFFFF", bold=True)
    b.text(cx + 22, cy + 84, "Java 21 · 8GB · 12 人在线", 11.5, "#FFFFFF", opacity=0.7)
    b.rect(cx + 22, cy + 102, 196, 7, "#FFFFFF", rx=4, opacity=0.24)
    b.rect(cx + 22, cy + 102, 134, 7, D_MINT, rx=4)
    b.text(cx + 228, cy + 109, "68%", 11, "#FFFFFF", mono=True)
    b.text(cx + 22, cy + 128, "内存 5.4 / 8.0 GB", 10.5, "#FFFFFF", opacity=0.7)


def p_setting_row(b, x, y, w):
    cw, ch = 400, 138
    cx, cy = x + (w - cw) / 2, y + (AREA_H - ch) / 2
    b.rect(cx, cy, cw, ch, D_CARD, rx=18, stroke=D_LINE, sw=1)
    for i, (t, s) in enumerate([("自动重启", "崩溃后自动拉起"), ("控制台字号", "12 sp")]):
        ry = cy + 34 + i * 56
        b.text(cx + 20, ry, t, 14.5, D_INK, bold=True)
        b.text(cx + 20, ry + 20, s, 11.5, D_MUTED)
        if i == 0:
            b.line(cx + 20, cy + 70, cx + cw - 20, cy + 70, D_LINE, 1)
        b.text(cx + cw - 26, ry, "›", 16, FAINT, anchor="end")


def p_input(b, x, y, w):
    bw, bh = 370, 56
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_FIELD, rx=14)
    b.rect(bx, by, bw, bh, None, rx=14, stroke=D_INDIGO, sw=1.6)
    b.text(bx + 16, by + 20, "服务器名称", 10.5, D_MUTED)
    b.text(bx + 16, by + 42, "my-server", 15, D_INK)
    b.line(bx + 116, by + 22, bx + 116, by + 46, D_INDIGO, 2)


def p_segment(b, x, y, w):
    bw, bh = 340, 46
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_FIELD, rx=18)
    b.rect(bx + 4, by + 4, (bw - 8) / 2, bh - 8, D_CARD, rx=15)
    b.text(bx + bw / 4, by + 30, "Java 版", 14, D_INDIGO, bold=True, anchor="middle")
    b.text(bx + bw * 0.75, by + 30, "基岩版", 14, D_MUTED, anchor="middle")


def p_switch(b, x, y, w):
    bw, bh = 340, 46
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_CARD, rx=16, stroke=D_LINE, sw=1)
    b.text(bx + 18, by + 30, "开机自启", 14, D_INK)
    tx, ty = bx + bw - 68, by + 10
    b.rect(tx, ty, 50, 26, D_MINT, rx=13)
    b.circle(tx + 37, ty + 13, 10, "#FFFFFF")


def p_progress(b, x, y, w):
    bw = 340
    bx = x + (w - bw) / 2
    by = y + AREA_H / 2 - 34
    b.text(bx, by, "下载服务器核心", 12.5, D_MUTED)
    b.text(bx + bw, by, "62%", 12.5, D_INDIGO, bold=True, mono=True, anchor="end")
    b.rect(bx, by + 16, bw, 10, D_FIELD, rx=5)
    b.rect(bx, by + 16, bw * 0.62, 10, D_INDIGO, rx=5)
    b.text(bx, by + 48, "1.2 GB / 2.0 GB", 10.5, FAINT)


def p_banner(b, x, y, w):
    bw = 380
    bx = x + (w - bw) / 2
    by = y + (AREA_H - 98) / 2
    for i, (bg, bar, txt, tc) in enumerate([
        (D_INDIGO_SOFT, D_INDIGO, "首次启动需要下载运行环境", D_INDIGO),
        (D_CORAL_SOFT, D_CORAL, "上次异常退出，已保留日志", D_CORAL),
    ]):
        yy = by + i * 54
        b.rect(bx, yy, bw, 44, bg, rx=12)
        b.rect(bx, yy + 10, 3, 24, bar, rx=2)
        b.text(bx + 16, yy + 27, txt, 12.5, tc)


def p_status_chip(b, x, y, w):
    bw, gap = 104, 14
    bx = x + (w - (bw * 2 + gap)) / 2
    by = y + AREA_H / 2 - 18
    for i, (bg, fg, t) in enumerate([(D_MINT_SOFT, D_MINT, "运行中"),
                                     (D_FIELD, D_MUTED, "已停止")]):
        xx = bx + i * (bw + gap)
        b.rect(xx, by, bw, 36, bg, rx=18)
        b.circle(xx + 20, by + 18, 4, fg)
        b.text(xx + 32, by + 23, t, 12.5, fg, bold=True)


def p_header(b, x, y, w):
    bw, bh = 420, 84
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_CARD, rx=14, stroke=D_LINE, sw=1)
    b.text(bx + 18, by + 26, "概览", 10.5, D_MUTED, bold=True)
    b.text(bx + 18, by + 58, "我的服务器", 20, D_INK, bold=True)
    for dx in (0, 18):
        b.rect(bx + bw - 34 + dx, by + 26, 12, 12, None, rx=3, stroke=D_MUTED, sw=1.4)


def p_navbar(b, x, y, w):
    bw, bh = 420, 80
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_CARD, rx=14, stroke=D_LINE, sw=1)
    for i, lab in enumerate(["概览", "服务器", "设置"]):
        cx = bx + bw * (i + 0.5) / 3
        if i == 0:
            b.rect(cx - 32, by + 12, 64, 28, D_INDIGO_SOFT, rx=14)
        b.rect(cx - 8, by + 20, 16, 13, None, rx=3,
               stroke=D_INDIGO if i == 0 else D_MUTED, sw=1.6)
        b.text(cx, by + 58, lab, 10.5, D_INDIGO if i == 0 else D_MUTED, bold=(i == 0),
               anchor="middle")


def p_dialog(b, x, y, w):
    b.rect(x, y, w, AREA_H, "#20242E", rx=12, opacity=0.13)
    cw, ch = 380, 136
    cx, cy = x + (w - cw) / 2, y + (AREA_H - ch) / 2
    b.rect(cx + 1, cy + 5, cw, ch, "#DCE1EA", rx=28)
    b.rect(cx, cy, cw, ch, D_CARD, rx=28)
    b.text(cx + 26, cy + 44, "删除服务器？", 18, D_INK, bold=True)
    b.text(cx + 26, cy + 74, "世界数据会被一并删除，无法恢复。", 12, D_MUTED)
    b.text(cx + cw - 26, cy + 116, "删除", 14, D_CORAL, bold=True, anchor="end")
    b.text(cx + cw - 82, cy + 116, "取消", 14, D_MUTED, anchor="end")


def p_widget(b, x, y, w):
    bw, bh = 420, 108
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, D_CARD, rx=18, stroke=D_LINE, sw=1)
    b.circle(bx + 26, by + 26, 5, D_MINT)
    b.text(bx + 40, by + 31, "运行中", 11.5, D_MINT, bold=True)
    b.text(bx + 20, by + 60, "我的生存服", 16, D_INK, bold=True)
    b.text(bx + 20, by + 84, "12 人在线 · 5.4 GB", 11, D_MUTED)
    b.rect(bx + bw - 92, by + 20, 72, 28, D_MINT_SOFT, rx=14)
    b.text(bx + bw - 56, by + 39, "停止", 11.5, D_MINT, bold=True, anchor="middle")


def p_terminal(b, x, y, w):
    bw, bh = 380, 132
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2
    b.rect(bx, by, bw, bh, "#12161F", rx=14)
    for i, (c, t) in enumerate([("#7C879B", "[12:04:01] Starting server"),
                                ("#8BE9A8", "[12:04:07] Done (5.6s)!"),
                                ("#E0E4EC", "[12:04:08] Listening on 25565")]):
        b.text(bx + 14, by + 30 + i * 26, t, 10.5, c, mono=True)


def p_radius(b, x, y, w):
    bw, bh = 96, 72
    total = bw * 3 + 28
    bx = x + (w - total) / 2
    by = y + AREA_H / 2 - 46
    for i, r in enumerate([18, 16, 28]):
        xx = bx + i * (bw + 14)
        b.rect(xx, by, bw, bh, D_INDIGO_SOFT, rx=r, stroke="#C0CDEA", sw=1.5)
        b.text(xx + bw / 2, by + bh + 22, f"rx={r}", 13.5, PRIMARY, bold=True, mono=True,
               anchor="middle")


def p_backdrop(b, x, y, w):
    bw, bh = 400, 112
    bx, by = x + (w - bw) / 2, y + (AREA_H - bh) / 2 - 12
    b.grad(bx, by, bw, bh, 16, ["#C9DCF5", "#8FB4E8"])
    b.rect(bx + 16, by + 12, bw - 32, 38, D_CARD, rx=13, opacity=0.93)
    b.rect(bx + 16, by + 62, bw - 32, 38, D_CARD, rx=13, opacity=0.93)
    b.text(bx + bw / 2, by + bh + 22, "卡片之间那条缝，是背景唯一露出来的地方", 11.5, MUTED,
           anchor="middle")


# ── 单元格 ────────────────────────────────────────────────────
def cell(b, x, y, w, title, note, painter, tokens, lock=None, fixed_note=None):
    b.rect(x, y, w, CELL_H, "#FFFFFF", rx=18, stroke=RIM, sw=1)
    b.text(x + 20, y + 30, title, 17.5, INK, bold=True)
    if lock:
        lw = len(lock) * 11.5 + 20
        b.rect(x + w - 20 - lw, y + 15, lw, 22, "#FDECEA", rx=11)
        b.text(x + w - 20 - lw / 2, y + 30, lock, 11, "#D2564C", bold=True, anchor="middle")
    elif note:
        b.text(x + w - 20, y + 30, note, 11, FAINT, anchor="end")
    painter(b, x + 14, y + 52, w - 28)
    b.line(x + 20, y + 204, x + w - 20, y + 204, "#EEF1F6", 1)
    if fixed_note:
        b.text(x + 20, y + 232, fixed_note, 11.5, MUTED)
        b.text(x + 20, y + 256, "换主题时这块保持原样", 11.5, FAINT)
    else:
        chip_row(b, x + 20, y + 230, w - 40, tokens)


def group_title(b, y, title, note, count):
    b.rect(M, y + 2, 4, 20, PRIMARY, rx=2)
    b.text(M + 16, y + 20, title, 19, INK, bold=True)
    tw = sum(19 if ord(c) > 127 else 10.5 for c in title)
    b.text(M + 20 + tw + 12, y + 19, note, 12.5, MUTED)
    b.text(M + CW, y + 19, count, 12, FAINT, anchor="end")
    return y + 40


def draw(b):
    b.rect(0, 0, W, 168, "#FFFFFF")
    b.rect(0, 167, W, 1, RIM)
    b.rect(M, 46, 5, 30, PRIMARY, rx=2)
    b.text(M + 20, 72, "主题组件图鉴", 32, INK, bold=True)
    b.text(M + 20, 102, "对着找零件：上面是它长什么样，下面写着它归哪些令牌管。"
                        "有颜色小块的 = 颜色令牌，没有的 = 圆角 / 强度 / 图片槽。", 13.5, MUTED)
    b.text(W - M, 66, "color / ext 改颜色 · shape 改圆角 · assets 换图 · metrics 改强度", 12, FAINT,
           anchor="end")
    b.text(W - M, 92, "标「语义锁」的零件颜色不能反转：停止必须红、运行必须绿", 12, FAINT, anchor="end")

    y = 196

    y = group_title(b, y, "按钮", "点了会执行动作", "5 个")
    cell(b, M, y, CELL_W, "启动大按钮", "未运行态", p_start_btn, [
        ("secondary", D_MINT), ("onSecondary", "#FFFFFF"), ("assets.accent", None),
        ("controlRadius", None)])
    cell(b, M + CELL_W + GAP, y, CELL_W, "停止 / 强制停止", "运行中态", p_stop_btn, [
        ("tertiary", D_CORAL), ("onTertiary", "#FFFFFF"), ("controlRadius", None)],
        lock="语义锁")
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "功能按钮", "渐变 + 金线", p_env_btn, [
        ("pillStart", D_INDIGO), ("pillEnd", D_INDIGO_DARK), ("gold", None),
        ("controlRadius", None)])
    y += CELL_H + GAP
    cell(b, M, y, CELL_W, "长条按钮", "整行切换", p_long_btn, [
        ("primaryContainer", D_INDIGO_SOFT), ("onPrimaryContainer", D_INDIGO),
        ("controlRadius", None)])
    cell(b, M + CELL_W + GAP, y, CELL_W, "主按钮 / 次按钮", "表单操作", p_pair_btn, [
        ("primary", D_INDIGO), ("onPrimary", "#FFFFFF"), ("outline", D_LINE),
        ("controlRadius", None)])
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "圆角三档", "shape 令牌", p_radius, [
        ("cardRadius", None), ("controlRadius", None), ("dialogRadius", None)])
    y += CELL_H + GAP + 26

    y = group_title(b, y, "卡片", "装内容的容器；底色、描边、标题色同源", "4 个")
    cell(b, M, y, CELL_W, "列表卡片", "可折叠分组", p_layout_card, [
        ("glassTop", D_CARD), ("cardRim", D_LINE), ("inkTitle", D_INK), ("cardRadius", None)])
    cell(b, M + CELL_W + GAP, y, CELL_W, "地址卡片", "信息 + 行内图标", p_addr_card, [
        ("glassTop", D_CARD), ("cardRim", D_LINE), ("primary", D_INDIGO),
        ("onSurfaceVariant", D_MUTED)])
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "设置行", "标题 + 说明 + 控件", p_setting_row, [
        ("inkTitle", D_INK), ("inkBody", D_MUTED), ("outline", D_LINE)])
    y += CELL_H + GAP

    hw = CELL_W * 2 + GAP
    b.rect(M, y, hw, CELL_H, "#FFFFFF", rx=18, stroke=RIM, sw=1)
    b.text(M + 20, y + 30, "Hero 状态卡", 17.5, INK, bold=True)
    b.text(M + hw - 20, y + 30, "概览页最上面那张大卡", 11, FAINT, anchor="end")
    p_hero_card(b, M + 14, y + 52, hw - 28, wide=True)
    b.line(M + 20, y + 204, M + hw - 20, y + 204, "#EEF1F6", 1)
    chip_row(b, M + 20, y + 228, hw - 40, [
        ("assets.hero", None), ("pillStart", D_INDIGO), ("pillEnd", D_INDIGO_DARK),
        ("heroTrack", "#FFFFFF"), ("heroBarStart", D_MINT), ("heroBarEnd", D_MINT),
        ("heroOnSoft", "#FFFFFF"), ("heroOnDim", "#FFFFFF"), ("cardRim", D_LINE),
        ("heroRadius", None), ("heroScrim", None)], max_lines=3)

    ix = M + hw + GAP
    b.rect(ix, y, CELL_W, CELL_H, "#F8FAFF", rx=18, stroke="#E1E8F5", sw=1)
    b.text(ix + 20, y + 30, "Hero 的两种画法", 17.5, INK, bold=True)
    blocks = [
        ("assets.hero 有图", D_INDIGO, [
            "铺插画，卡片文字仍是白色；",
            "图要自己压得住白字，压不住就调",
            "metrics.heroScrim（默认 0 不压）。"]),
        ("没有 assets.hero", D_MINT, [
            "用 pillStart → pillEnd 渐变兜底，",
            "和「功能按钮」同一组色。"]),
    ]
    by = y + 64
    for name, dot, lines in blocks:
        b.circle(ix + 24, by - 4, 3.5, dot)
        b.text(ix + 34, by, name, 12.5, PRIMARY, mono=True, bold=True)
        for j, ln in enumerate(lines):
            b.text(ix + 20, by + 24 + j * 19, ln, 11.8, BODY)
        by += 24 + len(lines) * 19 + 20
    b.text(ix + 20, y + 252, "scale=overflow 可让立绘探出卡片边界", 11.5, FAINT)
    y += CELL_H + GAP + 26

    y = group_title(b, y, "表单与状态", "输入、开关、进度、提示", "6 个")
    cell(b, M, y, CELL_W, "输入框", "聚焦态", p_input, [
        ("surfaceVariant", D_FIELD), ("outline", D_INDIGO), ("onSurface", D_INK),
        ("controlRadius", None)])
    cell(b, M + CELL_W + GAP, y, CELL_W, "分段控件", "两态切换", p_segment, [
        ("surface", D_CARD), ("primary", D_INDIGO), ("navRadius", None),
        ("onSurfaceVariant", D_MUTED)])
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "开关", "开启 = 绿", p_switch, [
        ("secondary", D_MINT), ("onSecondary", "#FFFFFF"), ("controlRadius", None)])
    y += CELL_H + GAP
    cell(b, M, y, CELL_W, "进度条", "下载 / 安装", p_progress, [
        ("surfaceVariant", D_FIELD), ("primary", D_INDIGO), ("onSurfaceVariant", D_MUTED)])
    cell(b, M + CELL_W + GAP, y, CELL_W, "提示条", "蓝 = 说明 · 红 = 出错", p_banner, [
        ("primaryContainer", D_INDIGO_SOFT), ("onPrimaryContainer", D_INDIGO),
        ("tertiaryContainer", D_CORAL_SOFT), ("onTertiaryContainer", D_CORAL)])
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "状态胶囊", "运行中 / 已停止", p_status_chip, [
        ("secondaryContainer", D_MINT_SOFT), ("onSecondaryContainer", D_MINT),
        ("navRadius", None)])
    y += CELL_H + GAP + 26

    y = group_title(b, y, "结构与容器", "整屏骨架；有背景图时这几处会变半透明", "4 个")
    cell(b, M, y, CELL_W, "顶栏", "标题 + 右侧操作", p_header, [
        ("surface", D_CARD), ("onBackground", D_INK), ("onSurfaceVariant", D_MUTED),
        ("navSurfaceAlpha", None)])
    cell(b, M + CELL_W + GAP, y, CELL_W, "底栏", "三个主入口", p_navbar, [
        ("surface", D_CARD), ("navActiveStart", D_INDIGO_SOFT), ("primary", D_INDIGO),
        ("onSurfaceVariant", D_MUTED)])
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "弹窗", "确认 / 危险操作", p_dialog, [
        ("surface", D_CARD), ("onSurface", D_INK), ("tertiary", D_CORAL),
        ("scrim", "#000000"), ("dialogRadius", None)])
    y += CELL_H + GAP
    cell(b, M, y, CELL_W, "整屏背景", "assets.background", p_backdrop, [
        ("background", D_BG), ("glassAlpha", None), ("navSurfaceAlpha", None),
        ("deep", "#000000")])
    cell(b, M + CELL_W + GAP, y, CELL_W, "终端日志", None, p_terminal, [],
         lock="固定色", fixed_note="终端有两套硬编码调色板，不跟主题走。")
    cell(b, M + (CELL_W + GAP) * 2, y, CELL_W, "桌面小组件", None, p_widget, [],
         lock="固定色", fixed_note="RemoteViews 读固定的颜色资源，主题改了也不变。")

    return y + CELL_H + 46


def main():
    b = Svg()
    H = draw(b)
    for name, backend in (("theme-parts.svg", Svg()), ("theme-parts.png", Png(H))):
        backend.rect(0, 0, W, H, BG)
        draw(backend)
        backend.save(os.path.join(OUT_DIR, name), H)
        print("wrote", name, W, "x", int(H))


if __name__ == "__main__":
    main()
