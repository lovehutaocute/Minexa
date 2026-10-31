"""一次性脚本：把 ui/ 下硬编码的 RoundedCornerShape(N.dp) 统一放大一档。

映射（与 McShape 的新令牌对齐）：
  3 -> 6   4 -> 8   6 -> 10  8 -> 12  9 -> 14  10 -> 16
  12 -> 18  14 -> 20  16 -> 22  18 -> 24  20 -> 28
大于 20 的值保持不动（那些通常是刻意的胶囊/圆形）。
"""
import re
import sys
from pathlib import Path

MAP = {
    "3": "6", "3.0": "6",
    "4": "8", "4.0": "8",
    "6": "10", "6.0": "10",
    "8": "12", "8.0": "12",
    "9": "14", "9.0": "14",
    "10": "16", "10.0": "16",
    "12": "18", "12.0": "18",
    "14": "20", "14.0": "20",
    "16": "22", "16.0": "22",
    "18": "24", "18.0": "24",
    "20": "28", "20.0": "28",
}

PATTERN = re.compile(r"RoundedCornerShape\(([0-9]+(?:\.[0-9]+)?)\.dp\)")


def main(root: str) -> None:
    total = 0
    for path in Path(root).rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        count = 0

        def repl(m: re.Match) -> str:
            nonlocal count
            raw = m.group(1)
            new = MAP.get(raw)
            if new is None:
                return m.group(0)
            count += 1
            return f"RoundedCornerShape({new}.dp)"

        new_text = PATTERN.sub(repl, text)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
            total += count
            print(f"{path}: {count}")
    print(f"total {total}")


if __name__ == "__main__":
    main(sys.argv[1])
