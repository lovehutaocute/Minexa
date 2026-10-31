# -*- coding: utf-8 -*-
"""P0 迁移：把 Mint/Coral 的「文字/图标」用法换成主题敏感的 MintInk/CoralInk。

只改单行的 `color = / tint = / statusColor = / iconColor = / tagColor = / accent =` 位置；
填充类（background(、containerColor、BorderStroke、copy(alpha=)）一律不动 ——
填充要保持原来的亮度，否则深色主题下会塌掉。
"""
import io
import os
import re

UI = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/java/com/mineserve/mobile/ui",
)

FG_KEYS = ("color", "tint", "statusColor", "iconColor", "tagColor", "accent")
KEY_RE = re.compile(r"\b(" + "|".join(FG_KEYS) + r")\s*=")
SKIP = ("background(", "containerColor", "BorderStroke", ".copy(alpha", "borderColor")
MINT_RE = re.compile(r"\bMint\b")
CORAL_RE = re.compile(r"\bCoral\b")


def process(path):
    with io.open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")
    changed = 0
    out = []
    for line in lines:
        if KEY_RE.search(line) and MINT_RE.search(line) or (
            KEY_RE.search(line) and CORAL_RE.search(line)
        ):
            if not any(s in line for s in SKIP):
                new = MINT_RE.sub("MintInk", line)
                new = CORAL_RE.sub("CoralInk", new)
                if new != line:
                    changed += 1
                    out.append(new)
                    continue
        out.append(line)
    if not changed:
        return 0
    text = "\n".join(out)
    for token in ("MintInk", "CoralInk"):
        imp = "import com.mineserve.mobile.ui.theme." + token
        if token in text and imp not in text:
            body = text.split("\n")
            base = "import com.mineserve.mobile.ui.theme." + token.replace("Ink", "")
            idx = None
            if base in body:
                idx = body.index(base) + 1
            else:
                for i, l in enumerate(body):
                    if l.startswith("import com.mineserve.mobile.ui.theme."):
                        idx = i + 1
            if idx is None:
                for i, l in enumerate(body):
                    if l.startswith("import "):
                        idx = i + 1
            body.insert(idx, imp)
            text = "\n".join(body)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    return changed


total = 0
for root, _dirs, files in os.walk(UI):
    for name in files:
        if name.endswith(".kt"):
            p = os.path.join(root, name)
            n = process(p)
            if n:
                print("%4d  %s" % (n, os.path.relpath(p, UI)))
                total += n
print("total replaced lines:", total)
