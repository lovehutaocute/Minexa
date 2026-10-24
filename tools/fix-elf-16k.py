#!/usr/bin/env python3
"""把 jniLibs 里的 .so 的 PT_LOAD p_align 提到 16KB（0x4000）。

背景：Android 15+ 的 16KB 页设备上，4KB 对齐的 ELF 会被系统判为不兼容，
vivo 等 ROM 每次启动都会弹「Android 应用兼容性」。Termux 上游的这些库是旧
工具链出的（p_align 是 14056/64/4368 这类杂值），重编不现实。

关键前提：**改 p_align 只有在段本身满足 16KB 同余时才是安全的** ——
即 p_offset % 16384 == p_vaddr % 16384（链接器按 vaddr 映射，两者差必须
是页大小的整数倍）。本脚本逐段验证，不满足的段**跳过并报告**，绝不盲改。

用法：python tools/fix-elf-16k.py app/src/main/jniLibs/arm64-v8a
"""
import struct
import sys
from pathlib import Path

PAGE = 16384
PT_LOAD = 1


def patch(path: Path) -> str:
    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF":
        return "skip: 不是 ELF"
    if data[4] != 2:  # ELFCLASS64
        return "skip: 非 64 位 ELF"
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    patched, congruent = 0, True
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, off + 8)
        p_align = struct.unpack_from("<Q", data, off + 40)[0]
        if p_align >= PAGE:
            continue
        # 链接器的硬约束：offset 与 vaddr 对页大小同余。不满足时改 p_align
        # 会让链接器把错误的文件区域映射进内存，宁可留着不动。
        if p_offset % PAGE != p_vaddr % PAGE:
            congruent = False
            continue
        struct.pack_into("<Q", data, off + 40, PAGE)
        patched += 1
    if patched:
        path.write_bytes(bytes(data))
    return f"patched={patched} congruent={congruent}"


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/jniLibs/arm64-v8a")
    for so in sorted(root.glob("*.so")):
        try:
            print(f"{so.name}: {patch(so)}")
        except Exception as e:  # noqa: BLE001
            print(f"{so.name}: ERROR {e}")


if __name__ == "__main__":
    main()
