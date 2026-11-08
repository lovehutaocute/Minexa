#!/usr/bin/env python3
"""修正内置 BDS 清单里写错的构建号（一次性维护脚本）。

Mojang 的历史包文件名带第四段构建号（`bedrock-server-1.20.80.2.zip`），而版本号本身
只有三段，构建号没有任何公开索引可查。内置清单是人工整理的，记错一个数字就变成一条
永远 404 的条目 —— 用户点下去只看到「所有源都不可用」。

做法：对每个可疑版本，探测 `x.y.z.N`（N=1..15，含补零写法）的直链，
取第一个真实存在的文件名；全都 404 就判定该版本不在存档里，从清单删除。

用法：
    python tools/fix-bds-catalog.py --check                 # 只体检，不改文件
    python tools/fix-bds-catalog.py --fix                   # 修正并回写 assets
    python tools/fix-bds-catalog.py --fix --only 1.16.100.4

只用标准库，HTTP/1.1（curl 在本机会被 TLS 指纹拦掉，Java/urllib 反而正常）。
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-{}.zip"
PREVIEW_BASE = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux-preview/bedrock-server-{}.zip"
ASSET = Path(__file__).resolve().parent.parent / "app/src/main/assets/bds/bds-versions.json"


def probe(url: str) -> int:
    """返回 206/200 表示存在；404 表示不存在；其它（超时/连接失败）返回 0。"""
    req = urllib.request.Request(
        url, headers={"Range": "bytes=0-0", "User-Agent": "Minexa/1.0 (catalog)"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def candidates(version: str) -> list[str]:
    """`1.20.80.1` → 试 1.20.80.{1..15}，补零写法也试一遍。"""
    parts = version.split(".")
    if len(parts) < 4:
        return []
    head = ".".join(parts[:3])
    out = []
    for n in range(1, 16):
        out.append(f"{head}.{n}")
        if n < 10:
            out.append(f"{head}.0{n}")
    seen, uniq = set(), []
    for v in out:
        if v not in seen:
            seen.add(v)
            uniq.append(v)
    return uniq


def resolve(version: str) -> tuple[str, str | None]:
    for cand in candidates(version):
        url = BASE.format(cand)
        if probe(url) in (200, 206):
            return version, cand
    return version, None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix", action="store_true", help="回写 assets 清单")
    ap.add_argument("--check", action="store_true", help="只体检（默认）")
    ap.add_argument("--only", default="", help="只处理这些版本，逗号分隔")
    args = ap.parse_args()

    data = json.loads(ASSET.read_text(encoding="utf-8"))
    versions = data["versions"]
    targets = [v["v"] for v in versions if v.get("c", "stable") == "stable"]
    if args.only:
        wanted = {x.strip() for x in args.only.split(",") if x.strip()}
        targets = [v for v in targets if v in wanted]

    # 先并行体检现状，只对 404 的做构建号扫描，避免 130×15 次无用请求
    def alive(version: str) -> tuple[str, bool]:
        idx = next(i for i, v in enumerate(versions) if v["v"] == version)
        return version, probe(versions[idx]["u"]) in (200, 206)

    with ThreadPoolExecutor(max_workers=8) as pool:
        status = dict(pool.map(alive, targets))

    dead = [v for v in targets if not status[v]]
    print(f"体检 {len(targets)} 条：可用 {len(targets) - len(dead)}，404 {len(dead)}")
    if not dead:
        return 0

    with ThreadPoolExecutor(max_workers=8) as pool:
        resolved = list(pool.map(resolve, dead))

    fixed = {old: new for old, new in resolved if new}
    gone = [old for old, new in resolved if not new]
    print(f"扫描到正确构建号 {len(fixed)} 条，判定不在存档 {len(gone)} 条")
    for old, new in fixed.items():
        print(f"  修正 {old} → {new}")
    for old in gone:
        print(f"  移除 {old}（官方存档里没有）")

    if not args.fix:
        print("（未回写；加 --fix 生效）")
        return 0

    kept = []
    for entry in versions:
        v = entry["v"]
        if v in fixed:
            entry = {"v": fixed[v], "c": entry.get("c", "stable"), "u": BASE.format(fixed[v])}
        elif v in gone:
            continue
        kept.append(entry)
    data["versions"] = kept
    ASSET.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"已回写 {ASSET}：{len(versions)} → {len(kept)} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
