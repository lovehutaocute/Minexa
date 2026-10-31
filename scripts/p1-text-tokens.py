# -*- coding: utf-8 -*-
"""P1 字阶收敛：把裸 `fontSize = N.sp` 换成语义档 `style = McText.X`。

只动 9/10/11/12/13/14 这六档（15/16/18/20/22 属于标题级，跟 Typography 走，不在此列）。
同一行若已带 lineHeight / fontWeight，保留原样 —— 参数优先级高于 style，覆盖即可。
14sp 走 McText.Title（SemiBold），若该行另有 fontWeight 会覆盖回原值，不会变样。
"""
import io
import os
import re
import sys

MAP = {
    9: "Micro", 10: "Micro",
    11: "Caption",
    12: "Body",
    13: "BodyWide",
    14: "Title",
    15: "Section", 16: "Section",
    18: "Page",
    20: "Display", 22: "Display",
}
# P1_SAFE=1 时只收敛 10–13 四档：字号、字重都不变，唯一变化是行高（19sp → 16/17/19），
# 且行高只会变小，不会把固定高度的行撑破。9sp 与 14sp 涉及字号/字重变化，留给逐屏看图再定。
if os.environ.get("P1_SAFE") == "1":
    MAP = {10: "Micro", 11: "Caption", 12: "Body", 13: "BodyWide"}
PAT = re.compile(r"fontSize = (\d+)\.sp")


def process(path):
    with io.open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")
    hits = 0
    out = []
    for line in lines:
        m = PAT.search(line)
        if m and int(m.group(1)) in MAP and "TextStyle(" not in line and ".copy(fontSize" not in line:
            line = PAT.sub("style = McText." + MAP[int(m.group(1))], line, count=1)
            hits += 1
        out.append(line)
    if not hits:
        print("0  " + path)
        return
    text = "\n".join(out)
    imp = "import com.mineserve.mobile.ui.theme.McText"
    if imp not in text:
        body = text.split("\n")
        idx = 0
        for i, l in enumerate(body):
            if l.startswith("import com.mineserve.mobile.ui.theme."):
                idx = i + 1
        body.insert(idx, imp)
        text = "\n".join(body)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print("%4d  %s" % (hits, path))


for p in sys.argv[1:]:
    process(p)
