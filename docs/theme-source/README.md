# 主题素材源（`theme-source/`）

给「生图模型重绘 → 矢量化 → 做主题」这条流程用的**源素材**。
不是展示图，是按 App 真实尺寸导出的零件。

生成：`python tools/gen-theme-source.py`
打包：同一条命令跑完会出 `docs/minexa-theme-source.zip`（44 个文件，约 240 KB）

```
minexa-theme-source/
  svg/     20 个矢量源（viewBox = dp）
  png/     20 个 3x 透明底图（丢生图模型用这批）
  contact-sheet.png / .svg   对照表
  manifest.csv               文件名 / dp 尺寸 / 令牌
  README.md
```

---

## 一、为什么是这么导的

| 决定 | 原因 |
|---|---|
| SVG 的 `viewBox` = dp（如 `0 0 328 46`） | 矢量化 / 手工改完之后贴回界面，直接按 dp 缩放，不会变形 |
| PNG 是 **3 倍 + 透明底** | 1dp = 3px，丢给生图模型不会被当成分辨率不足而"补细节" |
| 不放文字 | 文字会让模型重绘出乱码，矢量化也会把字变成一堆废路径 |
| 不用阴影 / 渐变发光 | 阴影转 SVG 是几百条路径，矢量化工具要么丢掉要么糊成一坨 |
| 圆形/三角用几何图形，不用图标字体 | 字体不存在时 `▶` 会变成方框（踩过） |
| 圆角、内间距全部来自真实代码 | 你重绘后尺寸对不上，贴回去就是歪的 |

## 二、两条路，按需要选

**A. 只换颜色（最快，10 分钟出一套）**

SVG 是纯形状 + `fill`/`stroke`，直接文本替换颜色即可：

```bash
# 例：把启动按钮的绿换成你自己的绿
sed -i 's/#2FBF87/#2AA5C9/g' svg/01-start-button.svg
```

一套主题只需要替换这几个默认色：

| 默认色 | 用在哪 |
|---|---|
| `#2FBF87` 薄荷绿 | 启动按钮、开关、Hero 进度条、状态胶囊 |
| `#3B4C9C` 靛蓝 | 主按钮、输入框聚焦描边、进度条、底栏选中 |
| `#2B3A7A` 深靛蓝 | 功能按钮渐变末端 |
| `#F97066` 珊瑚红 | 停止 / 强停 / 危险文字 |
| `#C9A65A` 金 | 功能按钮描边 |
| `#FFFFFF` `#E4E7EE` `#F0F1F5` | 卡片白、描边、输入框底 |

**B. 生图重绘（要风格化的时候）**

把 `png/` 里的图逐张丢给模型，一次性 prompt 建议：

```
Redesign this UI component in <你的风格> style. Keep the exact canvas size and
proportions. Flat vector look: solid fills, no shadows, no gradients blur, no text.
Keep the rounded corners. Output on transparent or pure white background.
```

要点：**强调 keep proportions / no shadow / no text**。模型最爱干的三件坏事就是改比例、加投影、加乱码文字。

出图后矢量化（Illustrator 图像描摹 / Inkscape 位图描摹 / potrace 均可），再按 `manifest.csv` 里的 dp 尺寸把画布设回原值。

## 三、文件清单

| 文件 | 组件 | dp 尺寸 | 对应令牌 |
|---|---|---|---|
| `01-start-button` | 启动大按钮 | 328 × 46 | `secondary`（语义锁：必须绿） |
| `02-stop-buttons` | 停止 / 强制停止 | 328 × 46（159 + 10 + 159） | `tertiary`（语义锁：必须红） |
| `03-env-button` | 功能按钮 | 116 × 36 | `pillStart`→`pillEnd`、`gold` |
| `04-row-button` | 长条按钮 | 328 × 36 | `primaryContainer` |
| `05-buttons-pair` | 主按钮 + 次按钮 | 328 × 44（156 + 16 + 156） | `primary`、`outline` |
| `06-list-card` | 列表卡片 | 328 × 96 | `glassTop`、`cardRim` |
| `07-address-card` | 地址卡片 | 328 × 96 | 同上 + `primary` |
| `08-hero-card` | Hero 状态卡 | 328 × 112 | `assets.hero`、`pillStart`、`heroTrack`、`heroBarStart` |
| `09-setting-row` | 设置行 | 328 × 56 | `inkTitle`、`outline` |
| `10-input-field` | 输入框（聚焦） | 328 × 56 | `surfaceVariant`、`outline` |
| `11-segment` | 分段控件 | 200 × 36 | `surface`、`primary` |
| `12-switch-row` | 开关行 | 328 × 48 | `secondary` |
| `13-progress` | 进度条 | 328 × 24 | `surfaceVariant`、`primary` |
| `14-banner` | 提示条 | 328 × 44 | `primaryContainer` |
| `15-status-chip` | 状态胶囊 | 76 × 26 | `secondaryContainer` |
| `16-header` | 顶栏 | 360 × 72 | `surface`、`onBackground` |
| `17-navbar` | 底栏 | 360 × 80 | `surface`、`navActiveStart` |
| `18-dialog` | 弹窗 | 300 × 176 | `surface`、`tertiary` |
| `19-terminal` | 终端（不跟随主题） | 328 × 200 | — |
| `20-widget` | 桌面小组件（不跟随主题） | 320 × 80 | — |

`manifest.csv` 是同一份数据的机器可读版（文件名 / 中文名 / dp 尺寸 / 3x 像素 / 令牌）。

`contact-sheet.png` 是对照表：左边名称 / 尺寸 / 令牌，右边按 1:1 比例画的组件，虚线框是画布边界。

## 四、这些图现在能直接用

客户端已支持**组件贴图**（`.mctheme` v2）：把导出的 PNG 放进包里的 `assets/tiles/`，
在 manifest 里登记槽位即可，不用改代码。

```jsonc
"assets": {
  "card":  "assets/tiles/card.webp",
  "startButton": "assets/tiles/start.webp"
},
"assetsMeta": {
  "card":  { "width": 328, "height": 96, "ninePatch": [18, 18, 18, 18] },
  "startButton": { "width": 328, "height": 46, "ninePatch": [16, 16, 16, 16] }
}
```

**14 个槽位全部接线，写进包里就生效**：

| 槽位 | 对应素材 | 建议 ninePatch |
|---|---|---|
| `card` / `cardCompact` | 06 列表卡片 / 07 地址卡片 / 08 设置行 | 18 |
| `startButton` | 01 启动大按钮 | 16 |
| `stopButton` | 02 停止/强停 | 16 |
| `pillButton` | 03 功能按钮 | 13 |
| `rowButton` | 04 长条按钮 | 14 |
| `primaryButton` | 05 主/次按钮（只吃主按钮） | 16 |
| `heroCard` | 09 Hero 卡 | 18 |
| `inputField` | 10 输入框 | 14 |
| `header` | 顶栏 | 0（整图拉伸即可） |
| `navbar` | 底栏 | 0 |
| `dialog` | 弹窗 | 28 |
| `banner` | 提示条 | 14 |
| `chip` | 状态胶囊 / 分段控件轨道 | 13 |

`primaryButton` 不会影响珊瑚红（删除/强停）与薄荷绿（成功）按钮 —— 那是语义色。
`header` 只有在给了图时才把顶栏撑到 56dp，没给图时版式不变。

九宫格边距建议直接用素材的圆角值：卡片 18、按钮 16、小胶囊 13。
完整说明见 `../THEME-PACK.md` 第七节；`../demo-tiles.mctheme` 是可导入的演示包。

**注意**：矢量化出来的图如果带阴影 / 发光，九宫格拉伸会让阴影跟着变形。
留白留够、阴影别画进图里。
