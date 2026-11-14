"""生成内置主题「萤火 · 白蝶」（id: firefly-pearl）。

素材来源：`tools/firefly_art.py` **程序化合成**。

为什么不再用用户那套生图组件图：那些图里烧死了假 UI（绿点、进度条、假标题条），
抹掉后在深色卡上留一块浅灰疤；装饰画得比控件还大，九宫格切不到，拉伸后蝶翅畸形；
每块面板还带一圈深描边，整屏像一排奖牌框。所以改成「平涂 + 1dp 描边 + 角上一枚小蝶翼」。

流程：合成 14 个槽位（浅色 + 深色两套）→ 生成整屏背景与缩略图 → 写 manifest/tokens
→ 调 themetool 打包校验。

用法：
    python tools/gen-firefly-theme.py
产物：
    app/src/main/assets/themes/firefly-pearl.mctheme
    docs/firefly-pearl.mctheme（同一份，便于分发/调试）
"""
import io
import json
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import firefly_art as ART  # noqa: E402

WORK_ROOT = os.path.join(os.path.dirname(PROJ), '_theme_kit')
BUILD = os.path.join(WORK_ROOT, 'firefly-pearl')
KIT = os.path.join(WORK_ROOT, 'kit', 'theme-kit')
TOOL = os.path.join(KIT, 'tools', 'themetool.py')
OUT_ASSET = os.path.join(PROJ, 'app', 'src', 'main', 'assets', 'themes', 'firefly-pearl.mctheme')
OUT_DOC = os.path.join(PROJ, 'docs', 'firefly-pearl.mctheme')

ID = 'firefly-pearl'
NAME = '萤火 · 白蝶'
VERSION = '1.1.0'
AUTHOR = 'Minexa Theme Kit'
DESC = '珍珠白玻璃 + 薄荷萤光：平涂面板、细描边，右下角一枚极淡的蝶翼。浅色/深色各一套。'

# 槽位 → (设计宽dp, 设计高dp, 圆角dp)
SLOTS = {
    'startButton':  (328, 46, 16),
    'stopButton':   (164, 46, 16),
    'pillButton':   (116, 36, 13),
    'rowButton':    (328, 36, 14),
    'primaryButton': (328, 44, 16),
    'card':         (328, 96, 18),
    'heroCard':     (328, 112, 18),
    'cardCompact':  (328, 56, 14),
    'inputField':   (328, 56, 14),
    'banner':       (328, 44, 14),
    'chip':         (76, 26, 12),
    'header':       (360, 72, 0),
    'navbar':       (360, 80, 0),
    'dialog':       (300, 176, 28),
}

# 取自素材本身的主色（见 README 的采样说明）
LIGHT = {
    'primary': '#253671', 'onPrimary': '#FFFFFF',
    'primaryContainer': '#DFE7FA', 'onPrimaryContainer': '#1B2A56',
    'secondary': '#1DB396', 'onSecondary': '#04241D',
    'secondaryContainer': '#CFF2E5', 'onSecondaryContainer': '#0B3B2F',
    'tertiary': '#F0736A', 'onTertiary': '#3A0F0B',
    'tertiaryContainer': '#FDE0DC', 'onTertiaryContainer': '#5A1B15',
    'background': '#F3F8F7', 'onBackground': '#17233B',
    'surface': '#FBFAF7', 'onSurface': '#17233B',
    'surfaceVariant': '#E7EEF4', 'onSurfaceVariant': '#4A5B78',
    'outline': '#C9D5E6', 'outlineVariant': '#DCE4EF',
    'error': '#D6453C', 'onError': '#FFFFFF',
    'surfaceTint': '#253671',
    'surfaceContainerLowest': '#FFFFFF', 'surfaceContainerLow': '#F7FAF9',
    'surfaceContainer': '#F1F6F5', 'surfaceContainerHigh': '#EBF2F1',
    'surfaceContainerHighest': '#E4EDEC',
    'surfaceDim': '#DBE5E4', 'surfaceBright': '#FFFFFF',
    'inverseSurface': '#1B2A3A', 'inverseOnSurface': '#EDF3F2',
    'inversePrimary': '#9FB6F2', 'scrim': '#000000',
}
LIGHT_EXT = {
    'glassTop': '#FCFDFB', 'glassMid': '#F7FAF8', 'glassBottom': '#F1F6F5',
    'glassRim': '#FFFFFF', 'cardRim': '#DCE4EF',
    'pillStart': '#253671', 'pillEnd': '#3B4C9C',
    'navActiveStart': '#CFF2E5', 'navActiveEnd': '#CFF2E5',
    'heroTrack': '#6E86B8', 'heroBarStart': '#1DB396', 'heroBarEnd': '#7FE3C6',
    'heroOnDim': '#C7D6F0', 'heroOnSoft': '#FFFFFF',
    'inkTitle': '#17233B', 'inkBody': '#43536E', 'inkHint': '#5D6B84',
    'night': '#0B1730', 'deep': '#0B1730',
    'hydro': '#1DB396', 'gold': '#C9A65A', 'goldInk': '#8A6A21',
    'topGlow': '#220E2A47', 'navSurface': '#FBFAF7F0',
}

DARK = {
    'primary': '#7FA0EC', 'onPrimary': '#10203F',
    'primaryContainer': '#2B3F72', 'onPrimaryContainer': '#DCE6FF',
    'secondary': '#38C9A9', 'onSecondary': '#04241D',
    'secondaryContainer': '#123E36', 'onSecondaryContainer': '#BDF3E4',
    'tertiary': '#F0857C', 'onTertiary': '#3A0F0B',
    'tertiaryContainer': '#4A211D', 'onTertiaryContainer': '#FFDAD5',
    'background': '#0C1424', 'onBackground': '#E6EEFB',
    'surface': '#152137', 'onSurface': '#E6EEFB',
    'surfaceVariant': '#1D2C46', 'onSurfaceVariant': '#B6C6DE',
    'outline': '#38507A', 'outlineVariant': '#25344F',
    'error': '#F2857C', 'onError': '#3A0F0B',
    'surfaceTint': '#7FA0EC',
    'surfaceContainerLowest': '#091020', 'surfaceContainerLow': '#111C31',
    'surfaceContainer': '#152137', 'surfaceContainerHigh': '#1B293F',
    'surfaceContainerHighest': '#22324A',
    'surfaceDim': '#0C1424', 'surfaceBright': '#2A3B55',
    'inverseSurface': '#E6EEFB', 'inverseOnSurface': '#1B2A3A',
    'inversePrimary': '#253671', 'scrim': '#000000',
}
DARK_EXT = {
    'glassTop': '#19263F', 'glassMid': '#16223A', 'glassBottom': '#121D33',
    'glassRim': '#2C3D5E', 'cardRim': '#273755',
    'pillStart': '#3E5AA8', 'pillEnd': '#2B4079',
    'navActiveStart': '#1D3A5C', 'navActiveEnd': '#1D3A5C',
    'heroTrack': '#40567F', 'heroBarStart': '#38C9A9', 'heroBarEnd': '#7FE3C6',
    'heroOnDim': '#A9BEDD', 'heroOnSoft': '#FFFFFF',
    'inkTitle': '#E9F1FF', 'inkBody': '#BECDE3', 'inkHint': '#8FA1BC',
    'night': '#080F1E', 'deep': '#080F1E',
    'hydro': '#38C9A9', 'gold': '#D8BA78', 'goldInk': '#E9D9AE',
    'topGlow': '#33000000', 'navSurface': '#152137F0',
}

SHAPE = {'cardRadius': 18, 'controlRadius': 16, 'dialogRadius': 28, 'heroRadius': 18, 'navRadius': 0}
METRICS = {
    'glassAlpha': 0.95, 'backdropScrimDark': 0.55, 'backdropTopGlowAlpha': 0.10,
    'heroCardOffsetRatio': 0.0407, 'accentArtAlpha': 0.55, 'navSurfaceAlpha': 0.94,
    'heroScrim': 0.0,
}
TYPO = {'titleLetterSpacing': 0, 'heroTitleSize': 20, 'useSerifTitle': False}


def make_background(path):
    """整屏背景：珍珠白→薄荷的竖向渐变 + 两处极淡的光斑（不抢内容）。"""
    w, h = 1080, 2378
    img = Image.new('RGB', (w, h))
    d = ImageDraw.Draw(img)
    top, bottom = (247, 251, 251), (232, 243, 242)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    glow = Image.new('RGB', (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-260, 120, 520, 900], fill=(26, 60, 40))
    gd.ellipse([w - 420, h - 900, w + 300, h - 100], fill=(20, 56, 48))
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    img = Image.blend(img, Image.eval(glow, lambda v: 255 - (255 - v) // 3), 0.22)
    img.save(path, quality=92)


def make_preview(path, tiles):
    """主题列表缩略图：背景 + Hero 卡 + 两颗按钮。"""
    w, h = 720, 720
    img = Image.new('RGB', (w, h), (243, 249, 248))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=(int(247 - 12 * t), int(251 - 6 * t), int(250 - 6 * t)))
    hero = Image.open(tiles['heroCard']).convert('RGBA')
    hero = hero.resize((600, int(hero.height * 600 / hero.width)), Image.LANCZOS)
    img.paste(hero, (60, 120), hero)
    start = Image.open(tiles['startButton']).convert('RGBA')
    start = start.resize((460, int(start.height * 460 / start.width)), Image.LANCZOS)
    img.paste(start, (60, 360), start)
    stop = Image.open(tiles['stopButton']).convert('RGBA')
    stop = stop.resize((250, int(stop.height * 250 / stop.width)), Image.LANCZOS)
    img.paste(stop, (60, 470), stop)
    card = Image.open(tiles['card']).convert('RGBA')
    card = card.resize((560, int(card.height * 560 / card.width)), Image.LANCZOS)
    img.paste(card, (60, 560), card)
    img.save(path, quality=92)


def main():
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(os.path.join(BUILD, 'assets', 'tiles'), exist_ok=True)
    os.makedirs(os.path.join(BUILD, 'assets', 'tiles-dark'), exist_ok=True)

    assets, meta, assets_dark, meta_dark, tile_paths = {}, {}, {}, {}, {}
    for slot, (dw, dh, r) in SLOTS.items():
        rel = 'assets/tiles/%s.webp' % slot
        rel_dark = 'assets/tiles-dark/%s.webp' % slot

        light = ART.render_tile(dw, dh, r, **ART.LIGHT_STYLES[slot])
        light.save(os.path.join(BUILD, rel), quality=92, method=6)
        dark = ART.render_tile(dw, dh, r, **ART.DARK_STYLES[slot])
        dark.save(os.path.join(BUILD, rel_dark), quality=92, method=6)

        assets[slot] = rel
        assets_dark[slot] = rel_dark
        tile_paths[slot] = os.path.join(BUILD, rel)
        m = {'width': dw, 'height': dh}
        if r:
            # 角就是圆角半径：装饰整枚塞在里面，拉伸时不会被拽变形
            m['ninePatch'] = [r, r, r, r]
        meta[slot] = m
        meta_dark[slot] = dict(m)
        print('%-14s %3dx%-3d r%-3d -> %s' % (slot, dw, dh, r, rel))

    make_background(os.path.join(BUILD, 'assets', 'background.webp'))
    assets['background'] = 'assets/background.webp'
    meta['background'] = {'width': 1080, 'height': 2378, 'scale': 'crop'}
    make_preview(os.path.join(BUILD, 'preview.webp'), tile_paths)

    tokens = {
        'schemaVersion': 1,
        'shape': SHAPE,
        'metrics': METRICS,
        'color': {'light': LIGHT, 'dark': DARK},
        'ext': {'light': LIGHT_EXT, 'dark': DARK_EXT},
        'typography': TYPO,
    }
    io.open(os.path.join(BUILD, 'tokens.json'), 'w', encoding='utf-8').write(
        json.dumps(tokens, ensure_ascii=False, indent=2))

    manifest = {
        'schemaVersion': 1,
        'id': ID,
        'name': NAME,
        'version': VERSION,
        'author': AUTHOR,
        'description': DESC,
        'minAppVersionCode': 61,
        'supportsDark': True,
        'preview': 'preview.webp',
        'tokens': 'tokens.json',
        'assets': assets,
        'assetsMeta': meta,
        'assetsDark': assets_dark,
        'assetsMetaDark': meta_dark,
    }
    io.open(os.path.join(BUILD, 'manifest.json'), 'w', encoding='utf-8').write(
        json.dumps(manifest, ensure_ascii=False, indent=2))

    pack = os.path.join(WORK_ROOT, ID + '.mctheme')
    subprocess.run([sys.executable, TOOL, 'pack', BUILD, '-o', pack], check=True)
    subprocess.run([sys.executable, TOOL, 'validate', pack], check=True)
    for dst in (OUT_ASSET, OUT_DOC):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(pack, dst)
        print('->', dst, os.path.getsize(dst) // 1024, 'KB')


if __name__ == '__main__':
    main()
