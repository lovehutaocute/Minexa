# 崩溃弹窗与智能日志分析 — 优化设计与落地实现方案

> 版本：v1 设计稿（2026-11-12）
> 范围：崩溃弹窗三视图重构、本次启动日志硬隔离、结构化智能崩溃分析（本地规则 + 历史案例 + 在线知识库）

---

## 0. 落地状态（v1 已实现）

| 方案项 | 状态 | 落点 |
|---|---|---|
| 三按钮 → 分析 / 关键日志 / 全部日志 | ✅ | `ui/screens/CrashReportsScreen.kt` |
| 本次启动硬边界（LaunchSession + 冻结） | ✅ | `runtime/LaunchSession.kt`、`TermuxRuntime.prepareMcLogFile/freezeLaunchSession/readSessionLog`、`server/SessionLogReader.kt` |
| 关键日志抽取（带行号、可回链） | ✅ | `server/KeyLogExtractor.kt` |
| 崩溃报告会话化 + `.json`/`.log` sidecar | ✅ | `server/CrashReportManager.kt` |
| 结构化报告模型 / 引擎 / 案例库 / 在线规则包 | ✅ | `server/CrashInsight.kt`、`CrashInsightEngine.kt`、`CrashCaseStore.kt`、`CrashKnowledgeBase.kt` |
| 分析页卡片化 + 证据回链 + 反馈闭环 | ✅ | `CrashReportsScreen.AnalysisTab`、`McViewModel.recordCrashFeedback` |
| MCP `get_crash_insight` | ✅ | `mcp/McpToolCatalog.kt`、`McpToolRunner.kt` |
| 在线规则包发布模板 | ✅ | `docs/crash-rules-pack.example.json` |
| 单测（会话隔离 / 抽取 / 引擎 / 回归） | ✅ | `SessionLogReaderTest`、`KeyLogExtractorTest`、`CrashInsightEngineTest` |

---

## 0. 现状与问题定位

| 现状 | 代码位置 | 问题 |
|---|---|---|
| 弹窗三个分段：`摘要 / 原始报告 / 完整日志` | `ui/screens/CrashReportsScreen.kt` → `CrashViewMode{Summary, RawReport, RunLog}` | 命名与语义不符：「原始报告」里混着 latest.log 最后 800 行，可能来自历史启动；「摘要」只是纯文本拼接，不是可执行结论 |
| 「完整日志」= `lastRunLog` | `McViewModel._lastRunLog` ← `TermuxRuntime.readLastMcRunLog()` | 已有会话偏移雏形（`lastMcLogStartOffset`），但只在 `wasRunning→!isRunning` 时刷新；启动即失败（`startupFailures` 路径）、自动重启、App 重启后可能为空或错位 |
| 崩溃报告「最近日志」 | `CrashReportManager.captureCrash` → `scanLatestLog(latest.log)` | 读的是服务端自己维护的 `logs/latest.log`，轮转/覆盖行为因核心而异（Forge/PNX/PocketMine 不一），**无法保证只含本次启动** |
| MC 原生崩溃报告 | `findLatestNativeCrashReport` 取目录内最新文件 | 只按文件时间取「最新」，上一次启动留下的原生报告会被打包进本次报告 |
| 归因 | `CrashReportAnalyzer` + `assets/rules/server_errors.json` | 纯本地正则/包含匹配；无历史案例、无在线知识、无置信度、无结构化输出，报告不可机读 |

结论：**「本次启动」目前没有任何硬边界**，这正是本次要解决的第一性问题；智能分析则要在「离线确定性分析」之上叠加本地案例与在线知识，且绝不能退化成「必须联网才好用」。

---

## 1. 弹窗交互设计

### 1.1 结构（自上而下）

```
┌─ 标题：核心名 · 版本 · exit=1 ─────────────────────┐
│  [ 分析 ]  [ 关键日志 ]  [ 全部日志 ]      ← SegmentedTabs（现有组件）
│  ┌──────────────────────────────────────────────┐   │
│  │ 范围条：本次启动 · 21:43:07 启动 · 共 3,184 行 │   │
│  │ （关键日志/全部日志页固定显示，分析页折叠进环境卡）│  │
│  └──────────────────────────────────────────────┘   │
│  内容区（按页签切换，见 1.2）                          │
│  [复制当前视图] [分享完整包] [去修 Java(条件)] [关闭]   │
└────────────────────────────────────────────────────┘
```

- 三个按钮改为：**分析 / 关键日志 / 全部日志**，默认落在「分析」。
- 按钮文案 key：`crash_tab_analysis` / `crash_tab_key_log` / `crash_tab_full_log`（4 套 values 同步；旧 key `crash_summary`/`crash_raw`/`crash_run_log` 保留一版兼容后删除）。
- 「范围条」是本次改造的核心可见承诺：一行小字说明当前查看的是**哪一次启动**的日志，避免用户误以为日志丢失。

### 1.2 三页签内容

**分析（Analysis）**
1. 结论卡：根因标题 + 置信度徽标（高/中/低）+ 一句话解释 + 「第一步该做什么」。
2. 证据卡：3–5 条关键行（截断 + 行号），每条可点「看日志」→ 跳「全部日志」并滚动高亮。
3. 修复步骤卡：编号步骤，能动作化的给按钮（重装 Java / 去环境页 / 打开 server.properties / 重装证书库 / 切 Java 版本），不可动作化的给命令与文件位置。
4. 知识来源卡：命中的规则 ID、相似历史案例（时间 + 结果）、在线知识条目（带来源与更新时间，标注「联网补充」）。
5. 反馈条：「这个建议解决问题了吗？」→ 已解决 / 未解决 → 回写本地案例库。

**关键日志（Key Log）**
- 只展示**本次启动**日志中自动抽取的崩溃相关片段：异常块（含完整栈）、Watchdog 转储、FATAL/SEVERE 行、启动失败关键行、崩溃前最后 N 行上下文。
- 顶部统计：`本次启动 · 命中 6 段 / 214 行（已排除历史启动日志）`。
- 每段带原因标签（如「首个异常」「Caused by 链」「Watchdog」「最后 20 行」），可折叠；点段头「定位」跳到全部日志对应行。
- 空态：`本次启动日志里没有检测到异常堆栈` + 两个出口：「查看全部日志」「做一次环境诊断」。

**全部日志（Full Log）**
- 本次启动全量输出，`LazyColumn` 虚拟化 + 行号；支持关键词搜索（高亮、上下跳转）、级别过滤（ERROR/WARN/INFO）、「只看崩溃点前后 50 行」快捷开关。
- 顶部固定范围条 + 「导出/分享本次日志」。
- 空态区分三态：进程未产生任何输出 / 日志读取失败 / 日志已被轮转（给诊断入口）。

### 1.3 「本次启动」硬边界（关键设计）

引入 **LaunchSession**：

```kotlin
data class LaunchSession(
    val id: String,            // "<startedAtMillis>-<pid>"，可读且唯一
    val dirName: String,
    val logFile: File,         // logs/mineserve-console.log（应用侧捕获文件）
    val startOffset: Long,     // 启动瞬间的文件长度（已有 lastMcLogStartOffset）
    val startedAt: Long,
    var frozenEndOffset: Long? = null,  // 进程退出/弹窗生成时冻结
    var fellBackToLatestLog: Boolean = false
)
```

规则：
1. 点「启动」即创建 session，记录 `startOffset`；日志视图与报告只读 `[startOffset, frozenEndOffset ?: EOF]`。
2. 进程退出或生成崩溃报告时**冻结** `frozenEndOffset`，此后即使自动重启，弹窗内容不变（弹窗读的是冻结切片，不是活文件）。
3. 启动即失败（命令拼装/Java 缺失/端口占用）同样要有 session：在 `McServerController` 抛 `StartupFailure` 前冻结，日志可能只有应用侧几行——这也是有效证据。
4. MC 原生 `crash-reports`：只取 `lastModified >= session.startedAt - 2s` 的文件，且最多 1 份；不满足则明确写「本次启动无原生报告」。
5. 回退策略：捕获文件不存在（老版本残留、核心直写 stdout 关闭）时回退 `logs/latest.log`，但必须做「本次启动标记过滤」（匹配启动 banner/时间戳前缀），并在 UI 标注 `回退模式：可能含少量历史行`，绝不静默混入。

> 已复用现有基建：`TermuxRuntime.prepareMcLogFile()` 已维护 `lastMcLogFile/lastMcLogStartOffset`，`readLastMcRunLog()` 已按偏移读取。本次是把「偏移」升级为显式 session 对象，并让报告/弹窗统一走它。

### 1.4 复制 / 分享语义（保持不变，但更准确）
- 复制：复制**当前视图**文本。
- 分享：始终是「完整包」= 本次启动全量日志 + 本次原生报告 + 结构化分析摘要（Markdown）。历史启动内容一律不打包。

---

## 2. 关键日志抽取算法

抽成独立组件 `server/KeyLogExtractor.kt`（从 `CrashReportManager.scanLatestLog` 提炼，复用现有 `KEY_MARKERS` / `SEVERE_MARKERS` / 缩进栈判定）：

```
输入：sessionLines: List<IndexedValue<String>>（有界切片，含行号）
输出：List<KeySegment>(startLine, endLine, reason, lines)

1) 块起点命中 KEY_MARKERS（Exception in thread / Caused by: / Watchdog / Server thread dump /
   "Failed to start" 等）→ 保留该行 + 后续缩进栈（≤60 行）
2) 单行命中 SEVERE_MARKERS（OutOfMemoryError / NoClassDefFoundError / FATAL / Could not / Unable to）
3) 尾部上下文：最后 20 行（无命中时即为唯一内容）
4) 去重（同一行号只出现一次）→ 按行号升序 → 段合并（相邻段间隔 ≤3 行则合并）
5) 双上限：≤600 行且 ≤256KB（沿用现有常量），超出从最旧的中间段开始丢，保留首段与尾段
```

产出的 `KeySegment` 同时作为分析引擎的证据来源（行号可回链），避免「分析结论」和「关键日志」两套逻辑各说各话。

---

## 3. 智能崩溃分析

### 3.1 三层知识源

| 层 | 来源 | 特性 | 优先级 |
|---|---|---|---|
| L1 内置规则 | `assets/rules/server_errors.json`（现状 20 条，可热更新规则包） | 离线、确定性、可解释（规则 ID） | 基线，永远先跑 |
| L2 历史案例 | 本地 `crash-cases/`：每次崩溃的指纹 + 结论 + 用户反馈 | 离线、个性化（同设备同核心最相关） | 与 L1 交叉验证，用于加权/补漏 |
| L3 在线知识 | 远端规则包 + 可选检索 API（公开崩溃知识库/官方 issue 摘要） | 需联网、可覆盖新崩溃 | 只做补充，失败静默降级 |

合并原则：**冲突不静默**。L1 与 L2/L3 结论不一致时并列展示，按置信度排序，并写明「规则判定 vs 相似案例判定」的依据差异。

### 3.2 解析流水线（离线优先）

```
① 归一化      去 ANSI / 时间戳前缀 / 折叠内存地址 / 路径替换
② 结构化提取  首个异常、Caused by 链、exitCode/signal、class 版本、loader 家族、
              MC 版本、Java 版本与厂商、堆大小、模组/插件数量、JVM 参数、
              native lib / GLIBC / 端口 / EULA / cacerts / fontconfig 等标记
③ 指纹        sha1(core + mcVersion + javaMajor + exceptionClass + top3Frames + normMessage)
④ 归因        L1 规则命中 → L2 top-3 相似案例（结构字段精确匹配加权 + token Jaccard）
              → L3 规则包/检索（异步、3s 超时）
⑤ 置信度      高：Identified 规则命中，或规则与历史案例一致
              中：仅 Possible 规则，或多源弱一致
              低：仅相似案例/在线弱匹配
⑥ 生成        结构化 CrashInsight(JSON) + 人读 Markdown 摘要
⑦ 落盘        crash-logs/crash_yyyMMdd_HHmmss.json（sidecar）+ cases/index.jsonl
```

全部在 `Dispatchers.Default` 上跑，输入是冻结的日志切片（有界），首屏目标 <500ms；L3 结果到达后以「联网补充」增量插入，不阻塞首屏。

### 3.3 结构化报告 Schema

```json
{
  "schemaVersion": 1,
  "crashId": "crash_20261112_214307",
  "session": { "id": "1794493387000-8123", "startedAt": 1794493387000, "endedAt": 1794493442000, "totalLines": 3184, "keyLines": 214, "truncated": false },
  "env": {
    "core": "fabric", "coreVersion": "0.16.5", "mcVersion": "1.21.1",
    "java": { "version": "21", "vendor": "termux", "heapMb": 2048 },
    "android": "14", "abi": "arm64-v8a", "device": "Redmi K70"
  },
  "trigger": { "exitCode": 1, "signal": null, "uptimeMs": 55000, "autoRestart": false, "userStop": false },
  "extraction": {
    "firstException": "java.lang.RuntimeException: Failed to setup fabric server",
    "causedBy": ["java.io.IOException: Request to ... failed", "java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty"],
    "classVersion": null, "components": ["fabric-server-launch.jar"],
    "markers": ["fontconfig", "trustAnchors"]
  },
  "cause": {
    "primary": {
      "category": "runtime", "ruleId": "java-cacerts-broken", "label": "Java 证书库缺失或损坏",
      "confidence": "high", "confidenceScore": 0.95,
      "explain": "JVM 根证书库为空，所有 HTTPS 握手失败，安装器下载 libraries 失败后退出。",
      "evidence": [{ "line": 27, "text": "Caused by: ... trustAnchors parameter must be non-empty" }]
    },
    "alternatives": [{ "ruleId": "fontconfig-missing", "label": "字体运行库缺失", "confidence": "low", "rejectedReason": "仅为启发式命中，且无法解释 HTTPS 失败" }]
  },
  "fixes": [
    { "id": "repair-truststore", "title": "一键修复 Java 证书库", "risk": "low", "expectedEffect": "恢复证书库，HTTPS 下载恢复",
      "steps": ["点弹窗内「一键修复」", "自动重装 Java + ca-certificates", "源不可用时用本机证书包重建 cacerts", "重新安装/更新核心"],
      "appAction": "action:repair-truststore" }
  ],
  "knowledge": [
    { "source": "L1", "ref": "java-cacerts-broken", "title": "内置规则：Java 证书库缺失或损坏" },
    { "source": "L2", "ref": "case_20261030_1012", "title": "同设备 Java 21 证书库损坏（已解决：重装 Java）", "similarity": 0.87 },
    { "source": "L3", "ref": "kb:termux/cacerts", "title": "Termux openjdk cacerts 符号链接说明", "url": "https://...", "updatedAt": "2026-10-02" }
  ],
  "stats": { "rulesEvaluated": 21, "rulesMatched": 2, "casesCompared": 37 }
}
```

`CrashInsight` 用 `@Serializable` 建模；`.txt` 报告继续保留（人读 + 兼容旧逻辑），`.json` 是机器可读的唯一真相。

### 3.4 本地历史案例库（L2）

- 写入：每次崩溃生成 insight 后自动落一条 case（fingerprint、cause、fixes、resolution=unknown）。
- 反馈闭环：分析页「已解决/未解决」→ 更新 resolution；已解决案例在后续匹配中权重 ×1.5，未解决 ×0.6（同一 fix 连续失败 2 次则在建议中标注「上次无效」）。
- 匹配：`score = 0.45*结构字段一致度(core/loader/mcVersion/javaMajor) + 0.35*token Jaccard(异常类+栈帧+markers) + 0.20*message 归一化相似度`；top-3，阈值 0.55。
- 容量：≤500 条 LRU，单条 ≤8KB；`index.jsonl` 常驻内存（只存指纹与摘要），详情按需读。

### 3.5 在线公共知识库（L3）

- **规则包分发**（首选，成本最低）：与 `server_errors.json` 同 schema 的 `crash_rules_v<N>.json`，带 `minAppVersion` + `sha256` 签名；复用 `data/DownloadSources.kt` 的多源/超时/换源，国内 CDN 优先、GitHub raw 兜底；成功即缓存到 `filesDir/kb/`，离线可用，损坏回退上一版。
- **检索 API**（可选，后续）：`POST /v1/crash/analyze`，请求**只含脱敏特征**（指纹、异常类、top 栈帧、loader/MC/Java 版本、markers），绝不发原始日志；3s 超时，失败静默。
- **隐私**：默认「仅本地分析」；在线开关首次开启时二次确认，列出将上传的字段；日志原文只有在用户主动「分享/上报」时才离开设备。
- **脱敏**（上传与分享前统一走一遍）：公网/内网 IP、端口、玩家名、绝对路径中的用户名、server.properties 中的 `online-mode`/`rcon.password`/`motd` 等敏感键值。

### 3.6 失败与降级

| 场景 | 行为 |
|---|---|
| 无网络 / L3 超时 | 立即用 L1+L2 出结论，分析页标注「联网补充不可用」（不报错、不阻塞） |
| 规则包损坏/签名不符 | 保留已缓存旧版，日志告警 |
| 分析引擎异常 | 退回 `CrashReportAnalyzer.analyze()` 的纯规则结果，UI 与现状一致（永不空白） |
| 日志为空/被轮转 | 结论降级为「环境类失败」，指向上方应用日志与诊断页 |

---

## 4. 代码落点（文件级改动）

| 文件 | 改动 |
|---|---|
| `runtime/TermuxRuntime.kt` | 新增 `LaunchSession`（或独立文件）；`prepareMcLogFile` 返回 session；新增 `freezeSession()`、`readSessionLog(session, maxBytes)`；`readLastMcRunLog` 改为 session 实现 |
| `server/SessionLogStore.kt`（新） | 会话日志读取、切片、行号索引、脱敏、回退 latest.log 的标记过滤 |
| `server/KeyLogExtractor.kt`（新） | 从 `CrashReportManager` 抽出标记表与块抽取，输出带行号的 `KeySegment` |
| `server/CrashReportManager.kt` | `captureCrash` 改为「session 日志 + 本次原生报告（mtime 过滤）」；同时写 `.txt` 与 `.json` sidecar；`CrashReport` 增加 `sessionId / hasInsight / primaryLabel` |
| `server/CrashInsight.kt`（新） | `@Serializable` 报告模型 + Markdown 渲染 |
| `server/CrashInsightEngine.kt`（新） | 流水线编排：抽取 → 指纹 → L1/L2/L3 → 置信度 → 报告 |
| `server/CrashKnowledgeBase.kt`（新） | L1 内置 + L3 规则包加载/缓存/签名校验；复用 `DownloadSources` |
| `server/CrashCaseStore.kt`（新） | L2 案例读写、相似检索、反馈回写、LRU |
| `server/CrashReportAnalyzer.kt` | 保持纯函数；新增 `matchAll(text): List<RuleHit(ruleId, label, level, evidenceLine)>` 供引擎消费（`analyze()` 行为不变，单测全绿） |
| `ui/screens/CrashReportsScreen.kt` | `CrashViewMode{Analysis, KeyLog, FullLog}`；分析页卡片化；日志页 `LazyColumn` + 行号 + 搜索 + 高亮 + 段跳转；范围条 |
| `ui/McViewModel.kt` | `currentCrashInsight` StateFlow；`readCrashReport` 并行读 `.json`；`openCrashLogAt(line)`；反馈事件 |
| `ui/McApp.kt` | 弹窗参数从 `runLog: String` 改为 `sessionLog: SessionLogHandle` |
| `res/values*/strings.xml` ×4 | 新增约 18 键（3 个页签、范围条、关键日志空态、置信度、证据/步骤/知识/相似案例标题、反馈、回退模式提示、在线关闭提示等） |
| `mcp/McpToolRunner.kt` + `McpToolCatalog.kt` | 新增 `get_crash_insight(crashId?)`，直接返回结构化 JSON（研发/自动化/客服排查用，与 App 内分析同源） |
| `test/.../CrashInsightEngineTest.kt` 等 | 新增：会话隔离（老日志不得出现）、关键片段抽取、规则优先级、相似案例匹配、脱敏、降级 |

---

## 5. 交互流程（端到端时序）

```
用户点「启动」
  └─ 创建 LaunchSession(id, logFile, startOffset) → 写入 app 日志 "[session] id=… 开始"

进程退出（异常）
  ├─ freezeSession(frozenEndOffset = EOF)
  ├─ captureCrash → crash_xxx.txt + crash_xxx.json(原始切片)
  ├─ CrashInsightEngine: 提取 → 指纹 → L1 → L2 → (异步 L3) → insight
  └─ 弹窗（默认「分析」页）

弹窗内
  分析 → 点证据「看日志」 → 全部日志（定位/高亮该行）
  分析 → 点修复按钮（如 goto:env/java）→ 关弹窗并跳转
  关键日志 → 段折叠 / 点「定位」→ 全部日志
  全部日志 → 搜索 / 级别过滤 / 分享本次日志
  反馈「已解决/未解决」→ 回写 case → toast「已记录，下次分析会更准」
```

---

## 6. 用户体验要点

1. **一句话结论优先**：分析页首屏只放「结论 + 置信度 + 第一步」，长文与证据折叠，避免手机上一屏全是文本。
2. **范围永远可见**：三个页签都带「本次启动 · 启动时间 · 行数」范围条；回退 latest.log 时必须显示「可能含少量历史行」，不允许静默。
3. **证据可回链**：每条结论都能跳到日志具体行并高亮——这是「可信」和「玄学诊断」的分界线。
4. **建议必须可执行**：能一键动作的给按钮（重装 Java、切版本、改 online-mode、重装证书库），不能的给「文件路径 + 命令 + 预期结果」。
5. **置信度分级而非伪精确**：高/中/低三档 + 一句话依据；低置信度时主动给「如何进一步确认」。
6. **不制造焦虑**：日志为空、在线不可用、分析失败都有明确、非技术化文案，且都给下一步出口。
7. **性能**：全部日志 `LazyColumn` 虚拟化（万行不卡）；切片有界（≤4MB / ≤600 关键行）；分析首屏 <500ms，L3 增量插入。
8. **一致性与可访问性**：沿用 `SegmentedTabs`、`McIcon` 尺寸、`Coral/Indigo/Muted` 语义色；等宽字体展示日志；支持大字号不破版；4 语言文案一次补齐。
9. **分享语义不变**：分享永远是完整包（本次启动全量 + 分析摘要），复制永远是当前视图，继承现有提示文案。
10. **闭环**：反馈按钮让知识库随使用变准——这是「智能」可持续的前提。

---

## 7. 分期落地

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| P0（1–2 天） | 三按钮重命名 + LaunchSession 硬隔离 + 关键日志抽取视图 + 范围条 + 原生报告 mtime 过滤 | 连续启动两次，第二次崩溃弹窗（三个页签）中不出现第一次任何一行；单测 `SessionLogScopeTest` + 真机截图 |
| P1（3–5 天） | `CrashInsight` JSON + 卡片化分析页 + 证据回链 + 反馈回写 + 本地案例库 | 用真实历史崩溃日志回放，主结论与人工判断一致；JSON 可被 MCP 读取 |
| P2（3–5 天） | 在线规则包分发（签名/缓存/降级）+ 脱敏 + 隐私开关 + `get_crash_insight` MCP 工具 | 断网时全功能可用且文案正确；规则包损坏能回退 |
| P3 | 案例聚类（发现规则缺口）、规则包灰度、可选检索 API | 线上误诊率下降，规则包可无发版更新 |

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| `latest.log` 行为因核心而异 | 以应用侧捕获文件 + 偏移为唯一真相；回退时显式标注 |
| 自动重启导致弹窗内容漂移 | 退出即冻结 `frozenEndOffset`，弹窗只读冻结切片 |
| 大日志 OOM / 卡顿 | 流式 + 有界 + 虚拟列表 + 切片冻结 |
| 误诊被放大（本次 cacerts 案例） | 置信度分级 + 冲突并列 + 证据行可见 + `Identified` 规则永远压过 `Possible` 启发式 |
| 在线依赖 / 隐私顾虑 | 默认离线；在线只发脱敏特征；超时静默降级；原始日志仅用户主动分享才外发 |
| 规则包被篡改 | sha256 + minAppVersion 校验，失败保留旧版 |

---

## 9. 与已修复问题的衔接

本次设计直接承接刚修复的「字体运行库缺失」误诊：`Identified` 优先排序、8KB 归因预览、cacerts 规则与自动重装已经落地。P0 的会话隔离解决「历史日志污染关键日志/全部日志」，P1 的结构化报告解决「结论不可机读、证据不可回链」，L2/L3 解决「只认识内置 20 条规则」的覆盖上限。
