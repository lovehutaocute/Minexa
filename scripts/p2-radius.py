# -*- coding: utf-8 -*-
"""圆角归一：把散落的 RoundedCornerShape(4/8/9/10/12.dp) 换成 McShape 令牌。

映射（与 Dimens.kt 的阶梯一致）：
  4dp  → McShape.Small   (6dp)   小标签/角标
  8dp  → McShape.Row     (10dp)  行容器、图标底盘
  9dp  → McShape.Row
  10dp → McShape.Row
  12dp → McShape.Card    (14dp)  卡片
2dp（4dp 高进度条的胶囊）不动 —— 那是 50% 圆角语义，换成令牌会变方。
"""
import io
import os
import re

UI = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/java/com/mineserve/mobile/ui",
)
MAP = {4: "McShape.Small", 8: "McShape.Row", 9: "McShape.Row",
       10: "McShape.Row", 12: "McShape.Card"}
PAT = re.compile(r"RoundedCornerShape\((\d+)\.dp\)")


def process(path):
    src = io.open(path, encoding="utf-8").read()
    hits = 0

    def sub(m):
        nonlocal hits
        n = int(m.group(1))
        if n in MAP:
            hits += 1
            return MAP[n]
        return m.group(0)

    out = PAT.sub(sub, src)
    if not hits:
        return 0
    imp = "import com.mineserve.mobile.ui.theme.McShape"
    if imp not in out:
        lines = out.split("\n")
        idx = 0
        for i, l in enumerate(lines):
            if l.startswith("import com.mineserve.mobile.ui.theme."):
                idx = i + 1
        if idx == 0:
            for i, l in enumerate(lines):
                if l.startswith("import "):
                    idx = i + 1
        lines.insert(idx, imp)
        out = "\n".join(lines)
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    return hits


total = 0
for root, dirs, files in os.walk(UI):
    if os.path.basename(root) == "theme":
        continue
    for name in files:
        if name.endswith(".kt"):
            p = os.path.join(root, name)
            n = process(p)
            if n:
                print("%4d  %s" % (n, os.path.relpath(p, UI)))
                total += n
print("total:", total)
