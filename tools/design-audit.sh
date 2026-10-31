#!/usr/bin/env bash
# 设计令牌体检。零依赖，随时可跑。
# 用法: bash tools/design-audit.sh [--verbose]
# 目标: 四类计数持续下降，不允许回升。超过阈值(默认)退出码 1，方便挂 CI。

set -u

SRC="app/src/main/java/com/mineserve/mobile"
VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

if [ ! -d "$SRC" ]; then
  echo "找不到 $SRC，请在项目根目录执行" >&2
  exit 2
fi

# 主题目录里的字面量是令牌定义本身，不算违规
is_theme() { case "$1" in */ui/theme/*) return 0;; *) return 1;; esac; }

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

while IFS= read -r f; do
  is_theme "$f" || echo "$f"
done < <(find "$SRC" -name '*.kt' | sort) > "$TMP"

count_dp=$(xargs -a "$TMP" grep -ho '[0-9]\+\.dp' 2>/dev/null | wc -l | tr -d ' ')
count_sp=$(xargs -a "$TMP" grep -ho '[0-9]\+\.sp' 2>/dev/null | wc -l | tr -d ' ')
count_color=$(xargs -a "$TMP" grep -ho 'Color(0x[0-9A-Fa-f]*)' 2>/dev/null | wc -l | tr -d ' ')

# 小于 48dp 的明确可点区域
count_touch=$(xargs -a "$TMP" grep -nE '\.size\(([1-9]|[1-3][0-9]|4[0-3])\.dp\).*clickable|clickable.*\.size\(([1-9]|[1-3][0-9]|4[0-3])\.dp\)' 2>/dev/null | wc -l | tr -d ' ')

# 禁止的装饰动画
count_motion=$(xargs -a "$TMP" grep -l 'rememberInfiniteTransition\|infiniteRepeatable' 2>/dev/null | wc -l | tr -d ' ')

# 禁止的渐变（紫/粉/靛色相）
count_gradient=$(xargs -a "$TMP" grep -lE 'Brush\.(linear|radial|sweep)Gradient' 2>/dev/null | wc -l | tr -d ' ')

echo "DESIGN AUDIT — $(date +%Y-%m-%d\ %H:%M)"
echo "────────────────────────────────────────────"
printf '%-34s %6s\n' 'dp 字面量（非主题目录）' "$count_dp"
printf '%-34s %6s\n' 'sp 字面量' "$count_sp"
printf '%-34s %6s\n' 'Color(0xFF..) 字面量' "$count_color"
printf '%-34s %6s\n' '小于48dp 的可点区域' "$count_touch"
printf '%-34s %6s\n' '装饰性无限动画（文件数）' "$count_motion"
printf '%-34s %6s\n' '渐变用法（文件数，需人工核）' "$count_gradient"
echo "────────────────────────────────────────────"

if [ "$VERBOSE" = "1" ]; then
  echo
  echo "## 可点区域 < 48dp"
  xargs -a "$TMP" grep -nE '\.size\(([1-9]|[1-3][0-9]|4[0-3])\.dp\).*clickable|clickable.*\.size\(([1-9]|[1-3][0-9]|4[0-3])\.dp\)' 2>/dev/null
  echo
  echo "## 装饰性无限动画"
  xargs -a "$TMP" grep -n 'rememberInfiniteTransition\|infiniteRepeatable' 2>/dev/null
  echo
  echo "## dp 字面量 top 12 文件"
  xargs -a "$TMP" grep -c '[0-9]\+\.dp' 2>/dev/null | grep -v ':0$' | sort -t: -k2 -rn | head -12
fi

FAIL=0
[ "$count_touch" -gt 0 ] && { echo "✗ 有 $count_touch 处触控目标低于 48dp，必须修"; FAIL=1; }
[ "$count_motion" -gt 0 ] && { echo "✗ 有 $count_motion 个文件用了装饰性无限动画"; FAIL=1; }

if [ "$FAIL" = "0" ]; then
  echo "✓ 交互硬约束通过（字面量计数见上，目标是持续下降）"
fi
exit "$FAIL"
