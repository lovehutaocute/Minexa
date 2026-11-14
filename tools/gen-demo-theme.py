"""生成「组件贴图演示包」docs/demo-tiles.mctheme。

用途只有一个：**验证 14 个槽位真的接通了**。每个槽位一张辨识度很高的纯色块，
装上主题后逐页看一遍，哪个零件没变就是没接线（或者画布尺寸没对上）。

不是设计参考 —— 真做主题请用 docs/theme-source/ 里的素材走生图流程。
"""
import io
import json
import os
import shutil
import subprocess
import sys
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
KIT = os.path.join(os.path.dirname(PROJ), '_theme_kit', 'kit', 'theme-kit')
BASE = os.path.join(KIT, 'themes', 'mist-neutral.mctheme')
WORK = os.path.join(os.path.dirname(PROJ), '_theme_kit', 'demo-build')
OUT = os.path.join(PROJ, 'docs', 'demo-tiles.mctheme')

S = 3  # 3 倍图


def tile(path, w_dp, h_dp, c1, c2, radius_dp, border=None, border_w=2):
    """画一张「有底、有边、圆角」的贴图（不写字，字由 App 画在上面）。"""
    w, h = w_dp * S, h_dp * S
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    # 竖向渐变
    grad = Image.new('RGB', (1, h))
    gd = ImageDraw.Draw(grad)
    for y in range(h):
        t = y / max(1, h - 1)
        gd.point((0, y), fill=tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)))
    grad = grad.resize((w, h))
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius_dp * S, fill=255)
    img.paste(grad, (0, 0), mask)
    if border:
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius_dp * S,
                            outline=border + (255,), width=border_w * S)
    img.save(path)


# 槽位 → (设计宽, 设计高, 起始色, 结束色, 圆角, 描边, ninePatch)
SLOTS = {
    'startButton':  (328, 46,  (200, 62, 148), (108, 34, 96),  16, (214, 175, 106), [16, 16, 16, 16]),
    'stopButton':   (164, 46,  (228, 87, 76),  (172, 48, 40),  16, (255, 255, 255), [16, 16, 16, 16]),
    'pillButton':   (116, 36,  (42, 165, 201), (28, 126, 155), 13, (255, 255, 255), [13, 13, 13, 13]),
    'rowButton':    (328, 40,  (222, 232, 255), (198, 214, 248), 14, (120, 150, 220), [14, 14, 14, 14]),
    'primaryButton': (328, 44, (86, 112, 214), (56, 74, 160),  16, (255, 255, 255), [16, 16, 16, 16]),
    'card':         (328, 96,  (255, 255, 255), (246, 249, 255), 18, (200, 212, 236), [18, 18, 18, 18]),
    'heroCard':     (328, 112, (70, 96, 180),  (38, 54, 120),  18, (140, 170, 235), [18, 18, 18, 18]),
    'inputField':   (328, 56,  (244, 247, 253), (234, 240, 251), 14, (160, 182, 220), [14, 14, 14, 14]),
    'header':       (360, 56,  (234, 241, 255), (214, 228, 253), 0,  (190, 208, 240), None),
    'navbar':       (360, 80,  (255, 255, 255), (243, 247, 255), 0,  (206, 218, 238), None),
    'dialog':       (320, 200, (255, 255, 255), (247, 250, 255), 28, (196, 210, 236), [28, 28, 28, 28]),
    'banner':       (328, 48,  (255, 246, 224), (253, 236, 198), 14, (232, 199, 122), [14, 14, 14, 14]),
    'chip':         (96, 28,   (237, 228, 255), (223, 210, 250), 13, (198, 179, 240), [13, 13, 13, 13]),
}


def main():
    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    os.makedirs(WORK)
    with zipfile.ZipFile(BASE) as z:
        z.extractall(WORK)

    tiles_dir = os.path.join(WORK, 'assets', 'tiles')
    os.makedirs(tiles_dir, exist_ok=True)

    assets = {}
    meta = {}
    for slot, (w, h, c1, c2, r, border, npatch) in SLOTS.items():
        rel = 'assets/tiles/%s.png' % slot
        tile(os.path.join(WORK, rel), w, h, c1, c2, r, border)
        assets[slot] = rel
        m = {'width': w, 'height': h}
        if npatch:
            m['ninePatch'] = npatch
        meta[slot] = m
    # 紧凑卡与普通卡共用一张：尺寸差得多但都是纯色圆角，拉伸看不出来
    assets['cardCompact'] = assets['card']
    meta['cardCompact'] = {'width': 328, 'height': 56, 'ninePatch': [18, 18, 18, 18]}

    man_path = os.path.join(WORK, 'manifest.json')
    man = json.load(io.open(man_path, encoding='utf-8'))
    # 注意：不动 schemaVersion —— App 只接受 1，组件贴图是**加法**（多几个 assets 键），
    # 升版本号会让所有老包被拒装。
    man['id'] = 'demo-tiles'
    man['name'] = '演示 · 组件贴图'
    man['version'] = '2'
    man['author'] = 'Minexa'
    man['description'] = ('组件贴图自检包：14 个槽位各一张高辨识色块。'
                          '装上后逐页翻一遍，哪个零件没变就是没接线。')
    man['assets'] = {**man.get('assets', {}), **assets}
    man['assetsMeta'] = {**man.get('assetsMeta', {}), **meta}
    io.open(man_path, 'w', encoding='utf-8').write(
        json.dumps(man, ensure_ascii=False, indent=2))

    pack = os.path.join(os.path.dirname(WORK), 'demo-tiles.mctheme')
    subprocess.run([sys.executable, os.path.join(KIT, 'tools', 'themetool.py'),
                    'pack', WORK, '-o', pack], check=True)
    subprocess.run([sys.executable, os.path.join(KIT, 'tools', 'themetool.py'),
                    'validate', pack], check=True)
    shutil.copyfile(pack, OUT)
    print('->', OUT, os.path.getsize(OUT) // 1024, 'KB')


if __name__ == '__main__':
    main()
