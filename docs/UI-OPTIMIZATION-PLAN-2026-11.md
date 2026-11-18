# Minexa UI 优化方案（2026-11 · feature/ui-refresh）

> 状态：**第一阶段已落地**（提交 `145f3c9`，构建/测试全绿）；本文档同时是第一阶段的设计依据与后续阶段的路线图。
> 依据：`UI-AUDIT-Minexa-ui-android-2026-10-30.md`（框架层全 PASS，执行层 942 处 dp 字面量等）、全页面交互排查（36 个 screen 文件）、`theme/` 设计体系现状。

---

## 0. 目标与约束

| 维度 | 目标 |
|---|---|
| 视觉 | 在不推翻现有 Linear/Geist 式冷灰蓝设计语言的前提下，补齐「动效层」这一最后短板 |
| 交互 | 消灭点不中的按钮、误触即造成不可逆后果的操作 |
| 性能 | 动效全部走 graphicsLayer / 补间，不引入逐帧布局；低端机双页并存窗口 ≤ 280ms |
| 兼容 | minSdk 26 全覆盖，无 API 级别分支 |
| 硬约束 | **零新增第三方依赖**；不改变业务逻辑与导航栈行为 |

---

## 1. 现状审计结论（为什么这么改）

1. **动效几乎空白**：36 个 screen 文件中仅 3 个有动画（Dashboard 启停按钮是唯一亮点）；页面切换、Tab 内容切换全部硬切，无 `AnimatedContent`/`Crossfade`。
2. **按压反馈全缺**：约 90 处 `.clickable` 全部使用默认 ripple，无一处按压缩放/触觉反馈。
3. **触控目标不达标**：14dp 刷新图标（PlayersScreen）、34dp 终端会话胶囊、30dp 下载页行内动作——均低于项目自定义的 `McIcon.Touch = 40dp` 标准。
4. **危险操作裸奔**：自动化任务删除、玩家封禁、终端清屏、清崩溃日志四处**无二次确认直接执行**；而备份页同类操作全部有确认——同类操作两种交互，不一致本身就是风险。
5. **框架层健全**：令牌（McSpacing/McShape/McIcon）、皮肤包引擎、毛玻璃（自研单次模糊）、深浅色 scheme 全部就绪 → 不需要重做设计系统，只需要**补交互层**。

---

## 2. 组件选型方案

### 2.1 第三方库评估（结论：全部不引入）

| 候选 | 用途 | 结论 | 理由 |
|---|---|---|---|
| **Haze** | 毛玻璃 | ❌ 已被否决 | `Glass.kt` 注释有完整论证：API ≤30 只能退化成半透明色块（等于没效果），RenderScript 兜底逐帧卷积反而拖垮老设备；项目背景是静态插画，自研「切主题模糊一次 + 位置采样」开销等于画色块 |
| **Lottie Compose** | 动效 | ❌ | 需要新增运行时依赖 + 动画 JSON 资产（无现成资产，自造质量不可控）；本应用是工具型界面，不需要叙事型动画 |
| **Compose Shimmer 库** | 骨架屏 | ❌（暂缓） | 可用 2 行 `infiniteTransition` 自实现，不值得为它进一个依赖；列入二阶段 backlog |
| **Material 3 内置**（已随 BOM 引入） | NavigationBar 指示器、Switch、FilterChip 等 | ✅ 继续用 | BOM 2024.12.01，`AnimatedContent`/`togetherWith` 已稳定 |
| **material-icons-extended**（已有） | 图标 | ✅ 继续用 | 已是全量 Outlined 风格，无需更换 |

**选型原则**：成熟项目引入第三方 UI 库的收益 = 组件外观 − 依赖体积 − 与自建皮肤/令牌体系的适配成本。本项目皮肤包引擎会替换组件的容器色/圆角/贴图，第三方组件不认识这套体系，引入即割裂。**因此所有新组件一律自建，并挂在现有令牌体系上。**

### 2.2 自建组件清单（第一阶段已交付）

| 组件 | 位置 | 说明 |
|---|---|---|
| `McMotion` 令牌 | `theme/Motion.kt` | 时长三档（Press 120 / Medium 220 / Page 280·Exit 200）+ 两档缓动；同一语义只用一档 |
| `Modifier.pressScale()` | `theme/Motion.kt` | 按压缩放回弹，`graphicsLayer` 实现（零布局开销）；pointerInput 只观察不消费，不干扰 ripple 与滚动 |
| `rememberTickHaptic()` | `theme/Motion.kt` | 轻触觉反馈（`VIRTUAL_KEY`，无振动器设备自动静默） |
| `ConfirmDialog` | `SharedComponents.kt` | 危险操作统一确认弹窗：Coral 确认 / Muted 取消 + 触觉反馈，收敛 10+ 处各写各的确认样式 |
| `PageForKey` | `McApp.kt` | 按导航 key 反查页面渲染，支撑转场期间旧页按自身 key 独立渲染 |

---

## 3. 动效方案

### 3.1 层级化动效策略（Material 3 Motion 语义）

| 层级 | 手段 | 时长 | 已覆盖 |
|---|---|---|---|
| 页面级（导航入栈/出栈） | `AnimatedContent` 方向感知滑动+淡入淡出 | 280 / 200ms | ✅ McApp 全站 |
| 内容级（Tab 切换、折叠） | `Crossfade` | 220ms | ✅ 玩家/插件/环境管理/崩溃报告四页 + 既有 SegmentedTabs 高亮滑动 |
| 微交互（按压、进度、状态） | `pressScale` / 补间 / 呼吸 | ≤120ms | ✅ 分段胶囊、悬浮返回键、进度条、Hero 状态点 |

**方向语义**：前进 = 新页从右缘滑入 1/4 屏宽；返回 = 镜像；Tab ↔ Tab = 纯淡入淡出（同级页面不用位移，位移只表达层级关系）。

### 3.2 已实施动效明细

- 页面转场（`McApp`）：见上；`SizeTransform(clip = false)` 消除高度突变
- `ProgressTrack` 进度补间：安装/下载进度离散上报不再跳格
- Hero 状态点呼吸（1s，α 1↔0.45）：**功能性动效**——「灯还亮着」是服务器存活的持续确认；停止/启动中不呼吸
- 悬浮返回键、`SegPill`：按压回弹 0.92/0.96 + 返回键触觉反馈
- 底部导航 Tab 切换：触觉反馈（M3 NavigationBar 自带指示器动画保留）

### 3.3 二阶段动效 backlog（按性价比排序）

1. **统一加载占位**：`LoadingHint`（居中 spinner+文案）+ 列表骨架屏（自研 shimmer），替换 17 个文件里散落的 10–18dp 小 spinner
2. **`animateItemPlacement`**：玩家列表、崩溃报告列表增删行时的位移动画
3. **`ModalBottomSheet` 弹出手感统一**：现存多处底部弹层参数各写各的
4. 谨慎原则继续有效：**禁止装饰性动效**（infinite 循环仅限状态指示一类功能语义）

---

## 4. 交互改进点

### 4.1 已修复（第一阶段）

| 问题 | 位置 | 修复 |
|---|---|---|
| 14dp 刷新图标当按钮 | PlayersScreen:444 | `IconButton` 36dp 命中区 |
| 会话胶囊 34dp / 关闭钮 28dp | TerminalScreen | 38dp / 32dp |
| 行内动作 30dp 高、9.5sp 文字 | DownloadScreen `RowAction` | 36dp / 13dp 图标 / 10.5sp |
| 家族分段 30dp | DownloadScreen `GroupTab` | 36dp |
| 删定时任务无确认 | AutomationScreen | `ConfirmDialog` |
| 封禁玩家无确认（持久影响） | PlayersScreen 详情弹窗 | `ConfirmDialog` |
| 终端清屏不可恢复无确认 | TerminalScreen 菜单 | `ConfirmDialog` |
| 清崩溃日志无确认 | DiagnosticsScreen | `ConfirmDialog` |
| 数据包空态裸 Text | JavaContentTabs | 统一 `EmptyHint` |

### 4.2 二阶段交互 backlog（优先级 P0 → P2）

- **P0 · dp 字面量收敛**：942 处裸 dp（前 5 个文件占 444 处）。补 `McSpacing.Tight(4)` 一档后机械替换 ~75%；密集文件：PluginsScreen(121)/NetworkScreen(94)/EnvManagerScreen(86)/DashboardScreen(73)/PlayersScreen(70)
- **P0 · 玩家/世界列表 LazyColumn 化**：✅ **已完成**（`145f3c9` 后续提交）。玩家页改为「固定头部（状态横幅 + 分段 Tab）+ 在线列表卡片占满剩余高度、卡内 LazyColumn 虚拟化」；世界页同模式。`McCard.content` 签名升级为 `ColumnScope` 接收者以支持卡内 `weight` 布局，既有调用点零改动。附带收益：分段 Tab 永远可达，不会被滚走
- **P1 · 终端调色板上移**：TerminalScreen 24 处、LogsScreen 14 处 ANSI 色抽成 `TerminalPalette` 常量对象（页面里只留引用）
- **P1 · 图标族统一**：170 Outlined vs 1 Filled（DashboardScreen:1204）混用
- **P1 · 深色验证手段**：6 个主页面补 `@Preview(name="Light")` / `@Preview(name="Dark", uiMode=UI_MODE_NIGHT_YES)` 双预览（当前 0 个 @Preview）
- **P2 · Predictive back**：14 个 Scaffold 页面仅 2 处 BackHandler，Android 14+ 侧滑返回无承接
- **P2 · detekt 静态检查**：补 `config/detekt`，把「触控目标 ≥ 40dp」「dp 走令牌」变成 CI 规则，防止回潮

---

## 5. 性能预算与验收标准

| 预算项 | 数值 | 验证方式 |
|---|---|---|
| 页面转场双页并存窗口 | ≤ 280ms（出屏侧 200ms） | 转场时长即预算 |
| pressScale 布局开销 | 0（graphicsLayer，不触发布局） | 实现层面保证 |
| 呼吸动效节点数 | ≤1/屏（Hero 状态点） | 代码 review |
| 最低设备目标 | minSdk 26，无 API 分支、无 RenderScript/RenderEffect | 编译期保证 |
| 回归门槛 | `assembleDebug` + `testDebugUnitTest` 全绿 | 已验证 ✅ |

## 6. 分支与提交

- 备份：`dd5e2b7`（master，改造前全部未提交变更）
- 改造：`145f3c9`（feature/ui-refresh，13 文件 +497/−73，含新文件 `theme/Motion.kt`）
- 合并策略：真机过一遍主流程（导航转场、四个 Tab Crossfade、五个确认弹窗、终端会话操作）后合入 master
