# 服务器属性页重构 · 动态配置表单

> 目标：属性页不再是一张写死的表单，而是**按当前核心目录下真实存在的配置文件生成 UI**，
> 从而同时兼容 Paper / Purpur / Fabric / Vanilla / BDS / PocketMine / PowerNukkitX
> 以及带 Mod 的自定义核心。

---

## 1. 改动的文件

| 文件 | 变化 |
| --- | --- |
| `app/.../server/PropertyCatalog.kt` | **新增**。配置项元数据字典 + 类型推断 + 排序 + 差值计算，纯 Kotlin 可单测 |
| `app/.../ui/screens/PropertiesScreen.kt` | **重写**。动态渲染 + 搜索 + 分组 + 专属控件 + MOTD 卡片；删除原先写死的 Java / PNX 两套表单（约 -250 行硬编码） |
| `app/.../ui/McViewModel.kt` | 保存成功后改为**合并写回**（`_serverProperties + props`），支持"只提交变动键" |
| `res/values*/strings.xml` ×4 | 新增 82 条文案（页面级 UI 22 条 + BDS 键 46 条 + 枚举选项 9 条 + Java 补充 2 条 + 其他），四套语言 1:1 对应 |
| `app/src/test/.../server/PropertyCatalogTest.kt` | **新增**。16 个用例守住排序 / 推断 / 未知键 / 数字别名 / 差值等底线 |
| `docs/properties-redesign/preview.html` | 视觉稿（浅色 + 深色），HTML 复刻，非真机截图 |

---

## 2. 四个核心模块怎么落的

### 2.1 动态解析与无损回写

- **读取**：`ServerPropertiesManager.readProperties(dir)` → 有序 `Map`，只按第一个 `=` 分割，
  转义序列原样透传（`ServerProperties.kt` 里已有的保真模型，本次未改）。
- **渲染**：`PropertyCatalog.buildRows(values, flavor)` —— 文件里有什么就渲染什么，
  字典命中的项用元数据，**没命中的项落进 P3 兜底区**（`known = false`，标题回退到原始 key）。
- **写回**：`PropertyCatalog.diff(values, original)` 只产出"值变过的键"，
  交给已有 `writeProperties` 的 `merge`（就地改行 / 缺失才追加）。
  → 注释行、空行、行序、未变动项全部原样保留。
- **未收录项的类型推断是保守的**：只有 `true/false/on/off` 才给开关，
  `*port*` 才给端口框，纯整数才给数字框，浮点（`0.35`）一律给文本框 ——
  猜错的开关会把 `0.35` 写成 `false`，那是真的改坏数据。

### 2.2 元数据字典（`PropertyCatalog`）

```kotlin
PropertyMeta(
    key, titleRes, descRes,     // 中文名 + 说明（副标题固定显示原始 key）
    group,                      // P0 核心 / P1 世界玩法 / P2 网络性能 / P3 未收录
    uiType,                     // Toggle / Segmented / Slider / Port / Number / Text / Multiline
    defaultValue, options, range,
    bedrockOptions, bedrockRange // 同一 key 在基岩版取值不同的地方才填
)
```

- 排序：`group.ordinal` → `key` 字母序（`buildRows` 里一次排好）。
- 覆盖：Java 版 ~72 键（含 `enforce-secure-profile`）+ BDS 24 键
  （`server-name` / `allow-cheats` / `server-portv6` / `default-player-permission-level`
  / `texturepack-required` / `content-log-file-enabled` / `tick-distance` /
  `server-authoritative-movement` / `player-movement-*` …）+ PNX 8 键。
- **加新键只需在 `list` 里加一行**，页面、搜索、分组、排序、保存全部自动生效。

### 2.3 专属控件分发

| 类型 | 控件 | 说明 |
| --- | --- | --- |
| Toggle | `Switch` | `true/false`，同时兼容 `on/off`、`yes/no` |
| Segmented | `FlowRow` + `SegPill` | `gamemode`（Java 4 档 / BDS 3 档）、`difficulty`、`default-player-permission-level`、`level-type`、`chat-restriction`、`server-authoritative-movement`；**数字别名 `0/1/2/3` 与单词互认**，当前值不在候选表里时把它渲染成一颗选中的胶囊（不假装"没选"） |
| Slider | `Slider` + 数值 | `view-distance` 2–32（BDS 5–32）、`simulation-distance` 2–32、`spawn-protection` 0–64、`tick-distance` 4–12；拖动走本地状态，抬手才落值 |
| Port | 5 位数字框 + 校验 | `server-port`、`server-portv6`、`query.port`、`rcon.port`、`management-server-port`，1–65535，越界红字且禁止保存 |
| Number | 数字框 + 校验 | `max-players`、权限等级、阈值；`player-movement-*-threshold` 允许小数 |
| Text / Multiline | 输入框 | 多行用于 `resource-pack-prompt`、`text-filtering-config` |
| MOTD | 顶部高亮卡 | `motd` / `server-name` / `sub-motd`：大文本框 + 16 个颜色块 + 5 个格式标签（B/I/U/S/×）+ **等宽字体实时预览**（解析 `§` 颜色与格式） |

### 2.4 交互

- 顶部搜索框（吸顶，不在滚动区里）：中文名 / 原始 key / 说明文字 三路子串匹配，实时过滤。
- **搜索时分组强制展开** —— 否则 P3 默认折叠会把命中项藏起来，看着像"没搜到"。
- 分组卡片显示条数，P3 默认折叠（>6 项时），避免生僻键淹没首页。
- 每行常显原始 key（`view-distance` 这种），改过的行显示「已修改」+ 「撤销」。
- 底部保存栏：`N 项待保存` / 无效输入时改显具体原因（端口越界 vs 非整数）+ 一键撤销全部；
  无改动或输入无效时按钮置灰；服务端运行中保存 → 弹「需要重启生效」确认。
- 有未保存修改时，返回与系统返回键都会拦截并确认。

---

## 3. 验证情况

| 项 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | ✅ 通过 |
| `:app:testDebugUnitTest --tests *PropertyCatalogTest*` | ✅ 16 项，0 失败 |
| `:app:assembleDebug` | ✅ `Minexa-arm64-v8a-debug.apk`（38 MB） |
| 新文案进资源表 | ✅ `aapt2 dump resources` 里 82 条 `prop_*`，简中/英/繁/fa 均有值 |
| **真机/模拟器实跑** | ⚠️ **未验证**：本机只有 x86_64 模拟器（`emulator-5554 offline`），APK 仅含 `arm64-v8a`，装不上 |

想自己跑一遍：

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot"
cd C:/Users/DELL/Desktop/AI/VSCode2/MCServerManager
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/Minexa-arm64-v8a-debug.apk
adb shell am start -n com.mineserve.mobile/.MainActivity
```

进「服务器配置」页确认三件事：① 条目数 = 配置文件行数（除注释）；
② 底部有「未收录配置」且能改；③ 改一项并保存后，用文件管理器看原文件注释还在。

---

## 4. 已知取舍

1. **只渲染文件里存在的键**。字典里有、但文件里没有的项不会凭空出现 ——
   写了就会往服务端不认识的行里塞值。首次启动前仍走「先启动一次服务端」引导页。
2. **枚举改值会写成规范单词**。文件里原本是 `difficulty=2`、用户点了「普通」会写成 `normal`；
   但**没动过的项根本不会被提交**，所以不会出现"只看一眼就把数字全改成单词"。
3. **BDS 只对 4 个键做了取值收敛**（`gamemode` 去旁观者、`view-distance`/`tick-distance` 范围、
   `difficulty` 通用）。其余键沿用 Java 语义，误改只会被服务端忽略或回退默认，不会崩。
4. **PocketMine 按 Java 风味渲染**（它的 `server.properties` 语义更接近 Java 版），
   没有为它单独维护一档取值表。

---

## 5. 真机反馈修订（2026-10-31）

第一版上线试机后，用户反馈「太拉」，截图暴露三处问题，已修：

### 5.1 一个真 bug：整页锁死（最要紧）

配置文件里 `management-server-port=` 这类**原本就是空值**的项，被 `invalidKeys`
无条件判成「非法端口」→ 页面一进来就渲染跨整宽的红字、保存按钮永久置灰。
用户既改不了任何东西，也看不出红字对应哪一行（那行在屏幕外）。

改法（`PropertiesScreen` 的 `invalidKeys`）：
- 空值：只有**用户亲手清空**的才算错，原文件里的空值交给服务端用默认值
- 非空但解析失败 / 端口越界：一律算错（写回去服务端会崩）
- 错误原因写在**对应输入框下面**，底部 dock 只显示「N 项需修正」

### 5.2 顶部四层同义标题 → 一行

原来是「返回栏：返回设置 → eyebrow：服务器配置 → 标题：服务器属性 → 当前核心：… · 各服务器核心配置相互独立」，
四层都在说同一件事，白底还在标题下方戛然而止（标题文字落在灰底上），看着像没加载完。

现在：`BackBar` 新增 `trailing` 槽位，标题只留「服务器属性」，核心名 + 条目数收进右侧胶囊
（搜索时胶囊显示命中数）；搜索框与顶栏共用一块白底，自绘填充式（`FieldGray` 底、42dp、无描边），
不再用 `OutlinedTextField` 的「白框贴白底」。

### 5.3 MOTD 卡吃掉半屏

16 个色块换行成两排（第二排只有 3 个，像没排好）+ 独立的「效果预览」黑条，
整卡近 380dp，把「核心设置」挤出首屏。

现在：
- 色板改 `LazyRow` **横滑一行**（右侧露半截提示可滑）
- 预览改成**服务器列表条目**：34dp 图标位 + 渲染后的彩色 MOTD + `Minecraft Server · 0/N` + 绿色信号条
- 卡高约 200dp

### 5.4 其他

- 行内不再画分隔线，靠 14dp 留白分组；`key` 从独占一行改为跟在标题后用 9.5sp 等宽灰字
  （70 项 × 每行省 14dp ≈ 省一屏）
- 开关自绘 40×22（M3 默认 Switch 的深灰轨道比旁边 13sp 标题还重），语义角色仍是 `Role.Switch`
- 底部由「横跨整宽的灰底大按钮」改为 dock：左状态（含撤销全部）、右按钮 40dp 高、自适应宽

### 5.5 验证

`compileDebugKotlin` ✅ / `assembleDebug` ✅（38 MB）/ `PropertyCatalogTest` 16 项 ✅。
真机仍未验证（本机模拟器 x86_64，APK 仅 arm64-v8a）。

---

## 6. 词典覆盖（2026-10-31 以官方配置全量对齐）

用户提供了一份官方 BDS 1.21.5x 的 `server.properties`（48 个键），比对后发现 22 个未收录，
已全部补齐并标注中文名与说明，覆盖率达到 **100%**（文件里的每个 key 都有中文标题）：

| 新增键 | 控件 |
| --- | --- |
| `transport` | 分段：raknet / nethernet |
| `server-udp-ports` | 文本（支持端口范围与 `公网:本地` 映射语法，不是单个数字） |
| `persistent-ticking-area-max-count` / `-max-chunks` / `transient-ticking-area-max-chunks` | 文本（官方允许 `unlimited`，数字框会把它过滤掉） |
| `content-log-console-output-enabled` | 开关 |
| `content-log-level` | 分段：error / warning / info / verbose |
| `compression-algorithm` | 分段：zlib / snappy |
| `server-authoritative-movement-strict` / `-dismount-strict` / `-entity-interactions-strict` | 开关 |
| `server-authoritative-entity-attack-request-validation` / `-range-validation` | 分段：disabled / observe / enforce |
| `server-authoritative-entity-attack-range-tolerance` | 数字（小数） |
| `player-position-acceptance-threshold` | 数字（小数） |
| `server-authoritative-block-breaking-pick-range-scalar` | 数字（小数） |
| `server-build-radius-ratio` | 文本（`Disabled` 或 0.0–1.0） |
| `allow-outbound-script-debugging` / `allow-inbound-script-debugging` | 开关 |
| `script-debugger-auto-attach` | 分段：disabled / connect / listen |
| `disable-custom-skins` / `disable-persona` | 开关 |

新增 12 个枚举选项文案（RakNet / NetherNet / 观察 / 强制 / 连接 / 监听 / 错误 / 警告 / 信息 / 详细 / zlib / Snappy），
四套语言各 56 条，共 224 条。字典总量 **129 项**。

单测新增 2 项（18 项全绿）：一项钉住 BDS 官方键全部收录，一项钉住这几个键的控件类型
（尤其 `unlimited` 必须是文本框、浮点阈值必须 `decimal`）。

## 7. 滑动卡顿修复（2026-10-31）

真机反馈「滑动的时候老是会卡一下」。

### 7.1 根因

`LazyColumn` 里**一个分组就是一张 `McCard`**，卡片内部 `rows.forEachIndexed` 把所有行
一次性组合。P0 组分动辄三十多行，每行还带 `DebouncedTextField` / `Slider` /
`SegmentedControl`，等于每滚过一屏就要组合几百个 Composable；更糟的是「整张卡」是不可
拆分的 Lazy item，只露出一半也得整卡组合 + 测量，滑动时必然掉帧。

### 7.2 改法：分组卡切成列表项

Lazy item 结构变成：

```
hero（MOTD 卡）
sec_hdr_P0  →  组头（上圆角 + 标题 + 计数 + 展开箭头）
row:motd    →  单行（直角白底）
row:server-port ……
sec_hdr_P1 ……
```

- **每行独立 item**（`key = row.key`，带 `contentType`）→ Lazy 只组合屏幕上那 6~8 行
- **视觉靠连续白底拼回来**：组头 `RoundedCornerShape(18,18,0,0)`、末行
  `RoundedCornerShape(0,0,18,18)`、中间行直角、左右都是 `McSpacing.Page` 且无间隙
- **折叠状态提到页面级**（`collapsedGroups` + `rememberSaveable`）：卡片不存在了，
  `McCard` 不再能自己管折叠。默认展开规则不变（P3 > 6 项收起，搜索时全展开）
- 代价：拼接处无法画 `Card` 的 1dp 描边与阴影（跨 item 会断），改成纯背景色区分

### 7.3 顺带修的两处重复计算

- `ServerListPreview` 里 `mcPreview(motd)` 每次重组都重新扫描 `§` 代码并构建
  `AnnotatedString` → 改为 `remember(motd, emptyText)`
- `MotdHeroCard` 的 hero item 加了 `contentType`，行 item 也标注 `contentType = "row"`

### 7.4 验证

`compileDebugKotlin` ✅ / `PropertyCatalogTest` 18 项 ✅ / `assembleDebug` ✅（38 MB）。
真机仍未验证（本机模拟器 x86_64，APK 仅 arm64）。
