# 更新日志

## 1.3.1

### 修复：frpc 引擎在线下载（元数据格式无法识别）

- 分发方 `edgecubemc.com` 的 frpc 元数据地址已从 `frpc0.70.1.json` 换到 `frpc0.69.1.json`，
  而该站是 SPA：不存在的路径不返回 404，而是回落 `index.html`（HTTP 200 + `text/html`）。
  于是下载请求"成功"、解析必然失败，用户只看到「元数据格式无法识别」。
- 现在改为**两阶段取元数据**：先试写死的最新地址，失败再查 `ecpkg/list.json` 目录清单现取
  （取 `type == "frpc"` 的 `packages`）⇒ 上游以后再换版本号不必发新版。
- 读元数据时嗅探首个非空白字符必须是 `{` / `[`，否则报「返回的不是 JSON」，并在错误里带上主机名。

### 新增：直接导入 `.ecpkg`

- 引擎分发物本身就是 `.ecpkg`（ZIP：`edgecube-package.json` + `<arch>/lib/libfrpc.so`），
  原先只认裸 `.so`，「手动下载后导入」实际走不通。现在 `FrpcRuntime.install` 按 ZIP 魔数分流，
  解包逻辑抽到 `FrpcEcpkg`，在线下载与手动导入共用一份。

### 改进

- 包直链优先 Gitee：元数据里 GitHub 排第一，而 GitHub 直连在部分网络下是静默超时，白等 15 秒才回退。
- 依赖页新增「打开发行页下载」入口（指向 Gitee 发行页）。
- 引擎版本号写入 `frpc/version.txt`，重启 App 后界面仍显示版本。
- 下载失败的错误信息按主机名聚合去重，不再只留最后一条。

## 1.3.0

### 新增：自动化定时任务（更多 → 自动化）

- **能力**：开服 / 停服 / 世界备份 / 完整备份 / 重启 / 游戏内广播 / 自定义命令七种动作。
- **频率**：固定间隔（分钟）、每 N 天同一时刻（N=1 即每天）、每周固定几天（可每 N 周）、5 段式 Cron。
- **触发方式**：`AlarmManager.setExactAndAllowWhileIdle` 只为「最近一个待触发任务」注册一个精确闹钟，
  触发后执行该任务、重算下次时间、再排下一个。用闹钟而非常驻协程的原因很直接——定时任务必须在
  App 进程已被系统清理、服务器未运行时也能触发（例如凌晨定时开服），这正是 WorkManager 与常驻协程都覆盖不了的场景。
- **失败可见**：任务抛异常时发一条通知，并照常推进 `lastRunMs` / `nextRunMs`，不会因为一次失败被反复触发。
- **替代关系**：1.2.x 设置页里的「每日定时开/停服」与「停止后自动备份」开关已移除，能力并入本页。

### 升级：1.2.x 每日定时设置一次性迁移

- 旧字段（`dailyStartEnabled` 等）仍在配置里，而 `BootReceiver` 与前台服务会照旧注册
  `ScheduleManager` 的闹钟 —— 界面已经没有入口可以关掉它，等于「看不见也管不了」。
- 因此启动时若发现「有旧设置且一条自动化任务都没有」，自动转成两条 `EveryXDays(dayInterval=1)` 的任务
  （每日开服 / 每日停服，时刻沿用旧值），并清空旧字段、按新配置重注册闹钟以取消旧闹钟。
- 逻辑在 `AutomationMigration`，纯函数 + 幂等；`AutomationMigrationTest` 覆盖「无旧设置」「开+停两条」
  「重复调用」「已有新任务时不补」四种情形。

### 新增：一键测速选择下载源

- 设置 → 下载源新增「依次尝试下载源」：按实际尝试顺序逐个探测，**命中第一个可用源就停**并自动选中。
- 探测发的是 `Range: bytes=0-1` 的 2 字节请求，URL 组装与真实下载完全一致，所以「测通」基本等价于「能下」。
- `BootstrapInstaller` 的镜像列表改为从 `DownloadMirror` 枚举派生，不再维护一份平行的硬编码副本
  （以前两边一旦不同步，设置里选的源和实际下载用的源就对不上）。

### 改进：下载源按实测结果重建

- 移除已失效的 `mirror.ghproxy.com`（超时）、`github.moeyy.xyz`（连接重置）、`gh.api.99988866.xyz`（TLS 失败）
  —— 留着只会让每次下载都白跑一轮回退。
- 新增 `cdn.gh-proxy.com`：它是实测**唯一**能代理 `api.github.com` 的镜像（其余几家对 api 请求一律 403），
  因此插件市场与更新检查的 API 请求改走「直连优先 + cdn.gh-proxy.com 兜底」。
- 修正阿里云 APT 仓库地址：`/termux/apt/termux-main` 是 404，正确路径为 `/termux/termux-main`。
- NeoForge 的 `maven-metadata.xml` 与 installer 增加 BMCLAPI maven 镜像兜底：官方 `maven.neoforged.net`
  在部分国内网络下会被 DNS 污染到完全不可达。
- `RemoteArtifact.metadataUrls()` 直接复用 `downloadUrls()`：非 GitHub 的自建地址不会再被套上反代前缀得到必然 404 的重试。

### 改进：唤醒锁按需持有

- 前台服务原先在 `onStartCommand` 里无条件 `acquireLocks()`，没有服务端在跑时也持着 CPU / Wi-Fi 锁白耗电。
- 现在改为按「MC 进程是否存活」同步锁：启动检测、状态刷新、配置变更三处统一走 `syncLocks(alive)`，
  锁偏好（`keepCpuWakelock` / `keepWifiLock`）由 `configFlow` 订阅缓存，改开关立即生效。

### 修复：启动时重读配置文件端口

- 端口真源是服务端配置文件（Java 系 `server.properties` 的 `server-port`，PowerNukkitX 是 `pnx.yml` 的
  `settings.port`），而 `McConfig.localPort` 只是它的镜像。
- 原实现启动链路直接吃镜像：用户在 App 之外把端口改掉后再启动，PNX / Allay 的启动链会用旧镜像**反写**配置，
  改过的端口又被打回去；地址栏与穿透也指向没人监听的端口。
- 现在 `McServerController.start()` 在 `migrateToAsciiServerDir` 之后调用 `adoptConfigPort()` 覆盖
  `launchConfig.localPort`。放在 controller 里意味着界面按钮、MCP `start_server`、定时任务、桌面组件、
  崩溃自动重启**全部**覆盖到。配置文件不存在或端口非法时原样返回，仍由核心首次生成配置时的默认端口兜底。
- 诊断页的端口检查文案顺带修正：原来标题与结果用的是 `cfg.localPort`，与现算的 `port` 可能不一致。

### 修复：控制台日志重复解析

- `consoleFlow` 的每个订阅者（VM 与前台服务）都会对**同一条**日志各调一次 `ConsoleLineParser.parse`，
  App 在前台时同一行被解析两遍。
- `parse` 只依赖入参字符串、无副作用，因此加了 64 条容量的 LRU 缓存，第二个消费者直接命中。

### 其它

- 删除已无调用方的 `ConsoleSocketServer`（控制台能力早已走 stdin/stdout 通道）。
- 概览页状态卡新增「白名单已开启 · 仅名单内玩家可进入」提示；`toggleWhitelist()` 同步 `serverProperties`，
  开关改完提示立刻更新，不用等下次读配置文件。

## 1.2.9

### 修复：隧道启动卡在「启动中」——状态认领错给了协程对象

- **问题**：点「启动隧道」后隧道实际已经跑通（引擎日志 `login to server success` → `proxy added` → `start proxy success`，节点也能连上），但界面始终显示「启动中」，日志里反复出现「进程状态变更(Starting/Running)，但没有后端认领本进程，界面不会更新」。
- **原因**：`FrpcBackend.start()` 把 `processManager.start(..., owner = this)` 写在了 `withContext(Dispatchers.IO) { ... }` 的 lambda **内部**。该 lambda 里的 `this` 是协程的 `CoroutineScope`（运行时为 `DispatchedCoroutine`），**不是** `FrpcBackend`。于是进程管理器的 `stateOwner` 指向一个从未 `attach` 过的对象，`stateSinks[stateOwner]` 查不到，引擎推送的所有状态被静默丢弃。
- **现在的行为**：在 `withContext` **块外**先用 `val self = this` 捕获后端自身，再把 `self` 作为 owner 传入。认领恢复正确，`Running` 能正常推到界面。
- **为什么之前没被测出来**：缺少引擎文件时 `processManager.start()` 会提前抛异常，已有的线程归属测试根本走不到传 owner 那一行。新增 `FrpcBackendOwnerIdentityTest` 专门断言 `start()` 之后 `stateOwner` 必须是后端自身、不得是协程对象。

### 修复：隧道启动路径的主线程网络请求

- **问题**：点「启动隧道」直接失败，日志只有一句「启动失败：null」，无从定位。
- **原因**：`FrpcBackend.start()` 会调用 `prepareConfig()`，而 SakuraFrp / ME Frp 两家的 `prepareConfig` 里是**同步** HTTP 请求（内部为阻塞的 `HttpURLConnection`）。调用方 `McViewModel.startTunnel()` 用的是 `viewModelScope.launch`（默认 `Dispatchers.Main`），启动路径却漏了 `withContext(Dispatchers.IO)` —— 网络请求因此落在主线程，被 Android 拦下抛 `NetworkOnMainThreadException`。该异常在 AOSP 里没有 message，所以日志只剩「启动失败：null」。同一界面里「拉取隧道列表」的调用点是切了 IO 的，唯独启动路径漏了。
- **现在的行为**：`FrpcBackend.start()` 整体包进 `withContext(Dispatchers.IO)`，三个 frp 系后端（自建 Frp / SakuraFrp / ME Frp）共用同一份保证。
- 顺带修正失败信息的可读性：异常 message 为空时回退到**异常类名**，不再产出「null」这种无法定位的日志。`TunnelManager` 与 `FrpcBackend` 两处失败路径一并处理。

### 修复：隧道启动失败后界面仍停在「启动中」

- **问题**：启动失败时 `TunnelManager` 对外暴露的状态仍是 `Starting`，界面既不显示错误也不结束等待。
- **原因**：`TunnelManager.start()` 只在调用后端之前读了一次状态；后端在 `start()` 内部把状态推进到 `Failed` 后，没有同步回 `_state`。`init` 里的状态收集协程是 `scope.launch` 异步启动的，时机上可能错过这次变化。
- **现在的行为**：`start()` 返回后**同步**拉取后端最新状态写入 `_state`，状态更新与 `start()` 返回对齐。

### 测试

- 新增 `FrpcBackendStartThreadTest`：用名为 `main` 的单线程执行器模拟主线程，断言 `prepareConfig` 不在其上执行。已通过反向验证（移除 `withContext` 后该测试确实失败）。
- 新增 `FrpcBackendOwnerIdentityTest`：断言 `start()` 之后 `stateOwner` 是后端自身。已通过反向验证（把 `owner = this` 放回块内后，报错为「实际=DispatchedCoroutine」，与真机日志一致）。
- 新增 `TunnelManagerStatePropagationTest`：断言启动失败后状态不得停留在 `Starting`。
- 新增 `TunnelFailureMessageTest`、`FrpcProcessManagerOwnerTest`。
- 引入 Robolectric 与 kotlinx-coroutines-test 作为测试依赖，使这类依赖 `Context` 的调度/线程问题可在纯 JVM 单测中验证，无需真机与账号。

## 1.2.8

### 修复：隧道排障日志——配置原文回显与 frpc 版本自报

- **问题**：ME Frp 隧道启动失败时只报 `json: unknown field "log"`，既不报行号也不回显配置；日志里只有一句「配置已写入 (343 字符)」，无从判断罪魁字段出自哪一行。**这个信息缺口直接导致上一轮排查方向走错**（误判为「平台用旧客户端写法、新版 frpc 不认 `log` 表」，而实际上 0.61.2 与 0.71.0 的 `ClientCommonConfig` 都有 `` `json:"log,omitempty"` ``，两个版本都接受该写法）。
- **现在的行为**：写入配置后把原文**逐行**打进隧道日志，值统一替换成 `***(长度)` —— `auth.token` 是等同于账号凭据的东西，原文照打会让用户求助时把 token 一起发出去；键名与长度保留，足以定位是哪一行、哪个字段、有没有被截断。`[[proxies]]` 与注释原样保留，因为它们的位置是判断「顶层键有没有插错位置」的依据。
- 启动前额外记一行 **`frpc --version` 自报的版本**。设备上可能同时存在下载版（`bin/frpc`）与 apt 版，而平台下发的配置字段集强依赖版本 —— 之前就是连「是哪个 frpc 在报错」都确定不了。取版本失败不影响启动。
- `unknown field` 的诊断话术改为指向「对照上方配置原文逐行核对，并把日志发给开发者」，不再给出未经证实的结论。
- 新增 `MeFrpConfigEchoTest`（5 项）钉住打码规则：token 值绝不出现、键名保留、表头与注释原样通过、嵌套键保留完整点号名、异常行不抛异常。

### 修复：frpc 改为下载版优先，apt 版仅兜底

- **问题**：`ensureFrp()` 的顺序是「缓存下载版 > apt 版 > 下载」，而 apt 版一旦存在就**永远赢**。这让固定版本号（`frpVersion`）完全失效：设备实际跑的是 Termux 源里滚动的那个版本，我们既不知道是哪一版，也无法保证它与平台下发配置的字段集匹配。真机日志里的 `frpc (apt): …/home/bin/frpc` 就是走的这条路 —— 上一轮把 `frpVersion` 升到 0.71.0 因此**根本没生效**。
- 现在下载版优先：缓存版存在即用；不存在就走镜像下载（带 sha256 校验）；**下载失败才**退回 apt 版，并在日志里说明「版本随 Termux 源漂移，可能与平台配置不匹配」。
- 顺带核实：Termux 源当前的 `frp` 包版本已是 `0.71.0`（`packages.termux.dev` 的 aarch64 `Packages`），与该修复方向不冲突。

### 修复：自动匹配 Java 时不再换掉你选的运行环境

- **问题**：填好 Token、选好隧道后点「启动隧道」，日志停在这一行就结束：
  ```
  [tunnel] ME Frp 配置已写入 (322 字符）
  [tunnel] 启动命令：…/frpc -c …/frpc-mefrp.toml
  [tunnel] json: unknown field "log"
  [tunnel] 隧道进程退出(code=1)
  ```
- **原因（两层，缺一不可）**：`/auth/proxy/config` 返回的成品 TOML 顶层带 `log = { to = "console", level = "info" }`，是**旧版 ME Frp 客户端**的写法；而上游原版 frpc 只认扁平键（`log.to`），见到整张 `log` 表直接拒绝解析并退出。与此同时，应用为了让日志进 stdout 会补一行 `log.to = "console"` —— 这一补反而让「是否已指定日志输出」的检查**提前命中**，平台那份 `log = {…}` 就被原样留下，于是新老写法同时出现在一个文件里。
- **为什么之前没暴露**：SakuraFrp 的成品配置用的是扁平键，`autoTLS` 那类差异又有 `sanitizeFrpConfig` 兜着，所以只有 ME Frp 这条链会撞上。
- **现在的行为**：拉回配置后先把 `log = { … }` **展平**成 `log.to` / `log.level`（平台给的日志级别不丢），再判断是否需要补 `log.to = "console"`，且一律插在第一个 `[[proxies]]` 之前。展平按行做，非 `log` 的行（含注释）原样保留；只有值确实是一整对 `{…}` 才展开，行尾注释不会把内容吃掉。
- **失败话术**：真遇到「本地 frpc 不认识的字段」时不再退化成裸 `隧道进程退出 (code=1)`，而是直接点名 `unknown field` 并说明该升级应用。
- 新增 `MeFrpConfigSanitizerTest`（6 项）钉住整形：展平而非删除、保留原日志级别、顶层键始终在 `[[proxies]]` 之前、无 `log` 时补 console、已是扁平键不重复插、其他内联表与行尾注释不受影响。

### 修复：自带 frpc 升到 0.71.0，并按实际版本向平台声明

- frp 固定版本从 **0.61.2 升到 0.71.0**（附官方 `frp_sha256_checksums.txt` 里 arm64 / amd64 两个架构的新摘要）。0.61.2 是 2024 年的版本，`transport.tls.*`、`virtualNet`、`featureGates` 等后续版本新增的字段都不认；各免费平台的下发配置跟着官方新版在走，旧二进制迟早会撞上 `unknown field`。
- SakuraFrp 的 `/tunnel/config` 声明版本（`FRPC_VERSION`）此前停在 **0.69.1**，与实际二进制 0.61.2 不一致 —— 声明版本决定了平台按哪套字段集发配置，**声明得比二进制新**同样是错配。两者现已对齐到同一个版本号。
- 新增 `FrpVersionConsistencyTest`（3 项）把这条约束焊死：两处版本号必须相等、`frpSha256` 必须同时覆盖 arm64/amd64 的 64 位摘要（缺摘要等于拒绝下载）、下载地址必须由版本号推导而不是写死。
- **注意**：单靠这一条不足以让设备换上 0.71.0 —— `ensureFrp()` 原本让 apt 版永远优先，升级后不会触发下载。该顺序已在下一条修复里改掉。
- 说明：已在跑隧道的用户不受影响；要立即换用新二进制，在「依赖管理」对 frp 卸载并重新安装一次。

### 修复：自动匹配 Java 时不再换掉你选的运行环境

- **问题**：在「运行环境」里明确选了 PRoot 容器后，启动 Paper 26.1 这类需要 Java 25 的核心时，应用会**静默把运行环境改成 Termux + Java 25**。而且这个改动会落盘 —— 你的显式选择被永久覆盖，下次启动直接换了链路。
- **原因**：自动匹配逻辑允许「连来源一起改」：所选来源不提供目标版本时，回退到「第一个提供该版本的来源」。而 Termux 确实提供 Java 25，所以这条替换**每次都静默成功**，不会像失败的替换那样暴露出来。
- **为什么这是错的**：运行环境是你唯一能指定「用哪个 libc 世界跑服务端」的入口，PRoot 的存在意义正是脱离 bionic。替用户换掉它，等于换掉整个运行前提。
- **现在的行为**：自动匹配（含版本升级、Forge ≤1.16 降级、Allay 强制 21+）**只在所选来源内调整版本**，来源全程不变。来源确实提供不了时明确报错，并把「换来源还是换版本」的决定权交还给你。
- 判断「这个来源能不能装这个版本」统一走 `JavaVendor.provides(version, distro)`，不再只看 `vendor.versions` —— 后者是两个发行版的并集，会把 focal 装不上的 Java 21 判成可用。
- 报错话术尽量给到底：若换个发行版就能装（focal 缺 Java 21、jammy 有），直接说明「把 Ubuntu 20.04 切换到 Ubuntu 22.04 即可使用 Java 21（切发行版不会删除已装的其他 Java 版本）」。
- 顺带修掉一处同源问题：Forge ≤1.16 的降级路径此前**无条件**把来源改成 Termux（注释还写着「Java 8/11 只有 Termux 提供」，这在 Java 8 归入 PRoot 后已不成立）。这会在用户选 PRoot 时把来源换掉，甚至写出「Termux + Java 8」这种列表里已不存在的组合。
- 新增 `JavaVendorStickinessTest`（7 项），钉住该策略依赖的事实：没有任何发行版提供 Java 25（因此 PRoot 下必须报错）、Termux 确实提供 Java 25（因此必须靠策略阻止而非指望它失败）、FCL 一个 Java 8/11 都没有。

### 新增：依赖管理页的分组折叠与 Ubuntu 版本胶囊

- 「依赖管理」页的 **Termux JDK / Pojav JRE / PRoot 容器** 三个分组现在各自可折叠展开，**默认全部折叠**。三个来源共 11 条版本行，全展开会把「卸载 / 重装 / 安装」三键顶出屏幕，而这三个按钮才是本板块的主要操作。
- 折叠时分组标题末尾标出「**已选 Java 17**」（仅当该来源持有当前选中项）—— 那三个按钮作用于当前选中项，不标出来的话折叠状态下根本看不出它们会作用到哪一份 Java 上。展开时不显示，因为选中的那一行有高亮边框。
- 「PRoot 容器」分组内新增 **Ubuntu 版本胶囊切换控件**：左「Ubuntu 20.04」、右「Ubuntu 22.04」，选中项为蓝色胶囊背景，切换时蓝色背景**滑动**到另一侧（260ms 缓动），而不是瞬移 —— 瞬移会让用户怀疑是否真的切换了，尤其在随后的版本列表也跟着变的时候，两个变化叠在一起很难判断因果。
- 字体、颜色、圆角、间距全部沿用现有取值：胶囊轨道用 `FieldGray`、选中块用 `Indigo`，圆角 17dp（内块 14dp 与轨道同心），文字 12sp。
- 切换发行版与版本**在同一次写入里完成**（`applyRuntimeSelection(..., prootDistro=)`）：两个发行版的可用版本不同，分两次写会撞上 config 落盘的 300ms 防抖，中间态还可能写出该发行版装不上的组合。

### 新增：PRoot Linux 容器（第三种运行环境）

- 新增 `JavaVendor.Proot`「PRoot 容器」分类，可在 App 内下载 Ubuntu rootfs（纯用户态，**不提供 Root 权限**）并用 apt 安装 glibc JDK。支持 **Ubuntu 20.04 (focal)** 与 **Ubuntu 22.04 (jammy)**。
- 可安装版本由发行版决定，也是唯一真相来源（`ProotDistro.availableJavaVersions`）：focal 提供 Java 8/11/17，jammy 提供 Java 11/17/21；**两者都不提供 Java 25**（Ubuntu arm64 源确实没有该包，提示语固定为「该容器不提供」，不是「暂未支持」）。
- 服务端目录以 `--bind=<host serverDir>:/srv/mineserve` 进入来宾，因此世界 / 插件 / 备份 / FTP / 文件管理全部沿用原有宿主路径，应用其余部分无需改动。
- 新增 `ProotContainerProfile`（`data/ProotContainer.kt`）作为「发行版 × Java 版本」的路径唯一真相来源，取代此前散落在 `TermuxRuntime` 里的硬编码；新增 `ProotContainerCatalogTest`（33 项）与 `JavaVendorCapabilityTest` 守卫版本矩阵与画像推导。
- 新增 `isProotGuestRuntime(vendor, version)` 作为唯一的路由谓词：`Proot → true`；`Termux → version == Java8`；`Fcl → false`。全部 `when (javaVersion)` 分支改为查这一个谓词，避免多处各自判断而漂移。

### 修正：Java 8 归入「PRoot 容器」分类

- **Java 8 不再列在「Termux JDK」下**。它在 Termux 分组里本来就跑在 proot 容器内，与「PRoot 容器」分组列出的是**同一个容器** —— 同一份运行时列两遍，用户会以为有两份而重复安装，或看不懂磁盘占用为何翻倍。现在 Java 8 只出现在它真正所属的「PRoot 容器」分组下。
- 分组说明同步订正：Termux 分组那句「Java 8 例外，它跑在 proot 容器内」在一个已经不含 Java 8 的分组里只会让人找不着，已改为直接说明 Java 8 属于下面的 PRoot 容器。
- PRoot 的 Java 8 版本行新增说明「**仅 Ubuntu 20.04（focal）提供此项**；发行版切到 22.04 后它不再可用」，避免用户换发行版后以为已装的 Java 8 丢了。
- **历史配置自动归一**：老配置里的 `Termux + Java8` 在**读盘时**被改写为 `Proot + Java8` 并把 `prootDistro` 对齐到 focal（`McConfig.normalizeProotRuntime()`，放在 `ServerRepository.decodeConfigLenient` 里，与既有的 `Stun → Frp` 迁移同一处）。归一是磁盘 → 内存的唯一入口做的，`configFlow` 与 `ensureConfigLoaded` 都经过它，不会出现「界面显示的发行版」与「实际启动用的发行版」分叉。
- **归一不改变实际运行的容器**：归一前后都是 focal + Java 8，已装环境不受影响、无需重新安装。`isProotGuestRuntime` 也仍然接受 `Termux + Java8`，作为归一之外的兜底。由 `JavaRuntimeCatalogTest` 的归一测试组钉住（合法性、指向同一容器、不误伤其他组合、幂等）。
- `LegacyJava8Distro` 成为遗留 Java 8 发行版的**唯一常量**，`legacyJava8Profile` 与配置归一共用，避免两处各写一份 focal 后出现「配置指向 A、启动找 B」。

### 新增：运行环境下拉框的「PRoot Linux 容器」选项

- 启动抽屉的「运行环境」下拉框此前只有 `Termux Java` / `Pojav JRE` / `PHP` 三项，`JavaVendor.Proot` **没有对应选项**：选了 PRoot 的用户打开抽屉会看到「Termux Java」被选中，而版本列表又是 Termux 的那一份 —— 界面显示的来源与实际运行的来源不一致。现已补上独立的「PRoot Linux 容器」选项（`ENV_PROOT_JAVA`）。
- 选中该选项时，版本行下方新增**发行版**选择（Ubuntu 20.04 / 22.04），且排在版本之前 —— 发行版决定版本列表，顺序反了会让人以为「换了发行版版本却没变」是 bug。
- 发行版与版本**一次写入**（`applyRuntimeSelection(..., prootDistro=)`）并按新发行版重新对齐版本：focal 与 jammy 的可用版本不同，沿用旧版本会写出一个装不上的组合。
- 下拉框里的版本列表只列**当前发行版确实提供**的版本（依赖页则是列并集再置灰，两处刻意不同，见 ARCHITECTURE）。
- `setJavaRuntime` 也加了发行版校验作为第二道保险：写进 config 的组合一旦装不上，用户要到启动失败时才会发现。

### 改进：单 JDK 卸载（apt 级）

- 此前 `deleteJava` 的 Proot 分支直接删除整个 rootfs，而一个 rootfs 里可以装多个 JDK（focal 有 8/11/17）——用户在界面上只点了「卸载 Java 17」，实际却把 Java 8、11 一起清掉了，属**静默数据丢失**级别的问题。
- 现在改为进容器 `apt-get purge` **只卸载目标版本**，rootfs 与其余 Java 版本保留（`containerJavaUninstallCommand` + `uninstallProotJava`）。
- 刻意**不做 `autoremove`**：多版本共存时它可能拔掉其他 JDK 仍需要的公共依赖（如 `ca-certificates` / `fontconfig`），问题要到下次启动才暴露。只 purge 目标包。
- 卸载前先跑 `dpkg --configure -a` / `apt-get -f install` 恢复半途中断的软件包事务，避免 dpkg 锁或未配置包导致卸载失败。
- 卸载后校验**所有**已知 JRE 布局（Java 8 的包把 JRE 放在 `jre/bin/java`，只查新 layout 会漏判），未清理干净则整个操作报告失败且**不改动任何宿主文件**。
- 新增「**删除容器**」独立入口与 `deleteProotContainer(distro)`，用于回收整个容器的磁盘空间；确认弹窗显示可回收容量。与「卸载某个 Java 版本」是两个不同粒度，因此入口分开。
- 新增 `env_proot_delete_container_confirm` 等文案，卸载与重装确认语改为如实说明影响范围（此前「不影响另一个来源与其他 Java 版本」对 Proot 而言是**假的**）。

### 修复

- **修复诊断页对 PRoot 来源误报「无需 proot」**：`prootRequired` 此前只判 `Termux && Java8`，选中「PRoot 容器」来源时该检查会显示「无需 proot」并标为 `NotApplicable` —— 而 proot 正是这条链路赖以启动的东西，容器起不来时这条会把用户引向完全错误的方向。现统一由 `isProotGuestRuntime(vendor, version)` 决定。
- **修复 Proot 来源的字体检查查错了地方**：`autoRepairRuntime` 此前只判 `javaVersion == Java8`，于是 `Proot + Java 17` 会掉进宿主修复分支 —— 对着**宿主** prefix 跑 apt 补 fontconfig、检查宿主 `etc/fonts/fonts.conf`，而来宾用的是容器里的那一套。结果是容器缺字体时报「已就绪」，或往宿主装一堆容器根本用不到的东西。现所有来宾链路（`isProotGuestRuntime`）统一走容器内检查与修复，并新增 `fontRuntimeDiagnostic(vendor, version)` 供诊断页使用；宿主侧的 `fontRuntimeReady(version)` 加了 `require` 挡住误用。
- **修复 Termux + Java 8 的 IPv6 判定错误**：该组合实际运行在 focal 容器内（与 `Proot + focal` 是同一个容器），来宾里是 glibc loader，按 bionic 构建的 `libipv6fix` 无法加载。此前 `ipv6ListenAvailable` 只看 `jvmFlavor`，用户选 Termux + Java 8 + PatchedJdk 时会得到 `true`，进而把 Allay 写成 `enablev6: true`，启动时抛 `UnsupportedAddressTypeException`。现先判「是否走来宾容器」再判 flavor。
- **修复 PRoot 下 `killOrphanMcProcess` 可能误杀健康服务端**：PRoot 链路下 `mcProcess` 注册的是 proot 追踪器本身，真实 java 是其子进程且 pid 不同；`killOrphanMcProcess(null)`（由 `fixUsrBin()` → `ensureAptWorking()` / `autoRepairRuntime()` / MCP `repair_runtime` 触达）在 PRoot 下会退化成 `.jar && nogui` 匹配，可能 SIGKILL 一个正常运行的服务端。现在同时用「排除 `descendantPids(trackedPid)` + 自身」与「未指定目标目录且已有追踪进程时跳过」两道守卫。
- 孤儿进程匹配抽到 `runtime/OrphanProcessMatch.kt`（`matchesManagedServer`），同时接受宿主 serverDir 与来宾 `/srv/mineserve` 两种形式，并如实记录「`/srv/mineserve` 无法区分多实例」这一已知局限（`OrphanProcessMatchingTest`，8 项）。
- **修复「请先停止服务端再重装 Java」文案**：该提示也用于卸载与删除容器，现改为中性的「请先停止服务端，再改动 Java 运行环境」（中英双份，`err_java_running` 此前只在 `strings_missing.xml` 里有英文）。

### 诊断与安装进度

- 诊断页新增**容器分层状态**检查（每个发行版一条）：把容器拆成 rootfs 是否存在 / shell 是否可用 / 就绪标记 / 已装版本列表四层报告。只报一句「未安装」会把两种代价完全不同的情况混为一谈 —— rootfs 缺失要重跑几百兆下载，而「容器好了但没装 Java」只是一次 apt 安装。仅「解压不完整」标为可一键修复。
- `installJava(onProgress)` 现在对 PRoot 来源也回调进度，按阶段划分：rootfs 下载解压 0–60（内部再按下载 85% / 解压修复 15% 细分）、容器内 apt 安装 60–100。此前只有 FCL 会回调，容器安装全程进度圈不动，数分钟的等待会被当成卡死。

### 文案与提示订正（三种 Java 来源逐条核对）

- Termux 分组说明不再笼统称「由 apt 安装、IPv6 由兼容层接管」——Java 8 例外，它跑在 proot 容器里，兼容层不生效。
- Termux 的 Java 8 标注「与 PRoot 容器分类下的 Ubuntu 20.04 是同一个容器」，避免用户误以为有两份、重复安装或误解磁盘占用。
- 新增 `JavaVendorCapabilityTest`：把「文案声称的能力」与「代码实际的能力」逐条对齐（Termux 的 Java 8 例外、FCL 自带 IPv6、PRoot 不支持 IPv6 监听、`Proot.versions` 等于各发行版可用版本并集、FCL 包体积 37–62MB）。文案与实现分居两个文件，改一边忘一边是常态，这类不一致不会崩、只会静默说错话，因此用测试钉住。

### 兼容性说明（已接受的破坏性变更）

- Termux + Java 8 与 PRoot + focal 已合并为同一条逻辑路径（`legacyJava8Profile` 就是 `ProotContainerProfile(Focal, Java8)`），**不再为旧版本做零迁移兼容**：删除了 `ProotDistro.reusesLegacyJava8Paths`、`LegacyJava8` 别名，以及 `java8Rootfs` / `java8ReadyMarker` / `ubuntuJava8Home` 三个遗留访问器与独立的 `installJava8Ubuntu()`。
- 容器路径统一按发行版命名（`focal-rootfs` / `jammy-rootfs`），不再编码 Java 版本——同一个 rootfs 本就能装任意受支持的 JDK，装完 Java 8 再装 Java 17 不必重下 rootfs。
- **代价**：升级前已装 Java 8 的用户会看到「未安装」，需重新安装一次。旧目录 `var/lib/mineserve/java8-ubuntu-rootfs` 与旧标记 `java-8-ubuntu-ready` 不再被引用，也**不自动删除**（避免误删用户数据），用户可手动删除以回收空间。
- **回滚注意**：旧版本 App 读到 `selectedJavaVendor = Proot` 会解析失败（枚举值未知）。

## 1.2.7

修复了一些已知 bug。

## 1.2.6

### 检测更新（重要修复）

- 修复「检测更新」直接报错的问题：v1.2.6 的发布说明留空后，GitHub Releases API 返回的是 `"body": null` 而不是 `""`，而响应模型把 `body` 声明为非空字符串。kotlinx.serialization 的默认值只在**键缺失**时生效，键存在但值为 `null` 会直接抛 `SerializationException`（`Expected string literal but 'null' literal was found at path: $.body`），三个镜像源全部解析失败，整个检测更新流程不可用。
- `body` 改为可空并统一归一为空字符串；解析器开启 `coerceInputValues`，`tag_name` / `assets` 等字段被上游写成 `null` 时回退到默认值，不再整包失败。
- 新增 `AppUpdateServiceTest`：用真实响应样本覆盖 `body: null`、字段为 `null`、键缺失三种边界，并补充版本号数字段比较（`1.2.16 > 1.2.6`、`26.2 > 1.21.11`）的回归用例。
- 修复 `compare()` 入参不对称：此前只对第二个参数剥 `v` 前缀，`compare("v1.2.6", "1.2.6")` 会得到 -1。当前调用方已归一所以线上不炸，但任何一处忘记归一都会把「有新版本」判成「无更新」而静默漏更新，现改为两侧统一归一。

### 核心版本检测（重要修复）

- 修复 Fabric、Forge、NeoForge、Quilt 四个核心**只能检测到 10 个旧版本、永远看不到新版本**的问题：这四个核心的版本列表此前是硬编码的静态列表（最高 1.21.4），并未真正联网。现已全部改为调用官方 API 实时获取——Fabric/Quilt 走 `meta.fabricmc.net` / `meta.quiltmc.org` 的 `versions/game`，Forge 解析 `promotions_slim.json`，NeoForge 解析 `maven-metadata.xml` 并反推 MC 版本。修复后四个核心分别可检测到 47 / 76 / 14 / 43 个版本，最新版本 `26.2` 可正常显示。
- 新增 `mcVersionComparator` 版本比较器，按数字段排序，正确处理 `26.2 > 1.21.11 > 1.21.4` 这类跨编号体系的情况（此前纯字符串排序会把 `1.21.4` 排在 `26.2` 之前）。
- 修复 NeoForge 版本前缀匹配串档：选择 MC `1.21` 时此前会命中 `21.11.45`（实为 1.21.11 的构建），现按点号段严格对齐，正确选中 `21.1.250`；同时过滤 `-beta` / `-rc` 预发布版本（`21.4.*` 下曾有 120 个 beta 混在正式列表中）。
- 修复 NeoForge 下载时的静默错误：当所选 MC 版本没有对应构建时，此前会回退下载 `<release>`（当前为 `26.2.0.86`，与所选版本完全无关），用户拿到一个装不上的 jar 且界面无任何报错。现在直接抛出明确错误。
- 所有版本请求均以失败回退包裹，断网时退回内置静态列表，不会出现空白列表。

### 依赖库打包（重要修复）

- 修复「一键安装依赖库」在部分机型（如荣耀 Magic 6）上执行 `apt-get` 立即崩溃的问题：原生依赖库此前只存在于代码内的清单里，而 `app/src/main/jniLibs/` 目录实际**不存在**，一个库都没打进 APK，运行时的释放逻辑始终空转，`apt`/`dpkg` 在动态链接阶段因找不到 `libandroid-glob.so` 直接退出（外在表现为等待数秒后报错、无任何日志、无网络请求）。
- 现已打包 21 个依赖库（约 7.7 MB）。除 `libtalloc.so` / `libandroid-shmem.so` 两个 deb 外，全部取自 App 自身安装的同一份 Termux rootfs（`bootstrap-2026.05.24-r1+apt.android-7`，SHA-256 与 `BootstrapInstaller` 中硬编码的校验值一致），确保运行环境版本完全对齐。
- 依赖闭包逐一核验：`libapt-pkg` 所需的 13 个库全部在内，不引入新的缺失链；`libandroid-glob.so` 的 NEEDED 仅 `libc.so`。
- `libtalloc.so.2` 与 `libandroid-shmem.so` 属于 proot 依赖链，**同样打进了 APK**：此前曾以「官方 rootfs 里没有 proot」为由把这两个库排除在清单外，代价是真机上 `bin/proot.bin` 链接失败 → Ubuntu 容器起不来 → Java 8 装不上，且 apt 需要 proot 才能装 proot 的依赖，用户没有任何自救路径。两个库已从 App 同一个 Termux 源（`libtalloc` / `libandroid-shmem` 两个 deb）取到，一并打包（见 `NativeLibraryBundler.kt` 第三批清单）。
- 第三方预编译库不再执行 strip，构建过程也不再依赖 NDK。
- 新增 `BundledLibrariesTest` 回归测试：静态比对「打包清单 vs jniLibs 实际文件」并校验 arm64 ELF 头，此类「清单有、文件没有」的错位今后由 CI 直接拦截。

### 运行环境（Java / PHP）

- 运行环境改为**用户选择优先、完全生效**：不再根据核心类型反向改写用户选择。启动前只做一致性校验（PocketMine-MP 需 PHP、其余核心需 Java），不匹配时给出明确提示，而不是悄悄更改。
- 新增 `applyRuntimeSelection` 原子写入方法：此前 `setRuntimeKind` 与 `setPhpVersion` 是两次独立的读-改-写，而配置落盘带 300ms debounce，第二次调用会读到过期快照并覆盖第一次结果（表现为「切到 PHP 后按钮又变回 Java」）。现已合并为单次写入。
- 概览页新增「运行环境」入口与选择弹窗（Java / PHP 分栏、带动画指示器、固定列表高度避免切换时弹窗高度抖动）。
- 环境与依赖管理页新增「PHP」分类；「安装 PHP」卡片从依赖模块移入该分类。

### 崩溃报告

- 崩溃报告不再只截取 `latest.log` 最后 200 行：Paper Watchdog 的线程转储动辄数百行，单纯截尾会把最关键的 `Current Thread: Server thread` 整段丢掉。现按关键词成块提取关键片段（Watchdog、异常栈、`Caused by:`、`OutOfMemoryError` 等）并保留其后缩进栈行，再补上最近 800 行日志作为上下文，两者按原顺序去重输出。

### 服务端配置

- 修复 PowerNukkitX 配置检测错核心的问题：此前统一探测 `server.properties`，导致 `pnx.yml` 尚未生成时表单即被放行。现按核心分别判定（Java 系 `server.properties`、PNX `pnx.yml`）。
- 配置尚未生成时展示引导卡片（含「立即启动服务端」「刷新配置状态」），不再展示空的可编辑表单。

### 界面

- 页面顶部标题栏高度缩短，概览页标题栏新增 QQ 群快捷入口（点击弹出群号，支持复制与一键加群），并移除概览页的「加入QQ群」大卡片。
- 下载页核心名重名拦截：同名服务器会在下载前直接拒绝，避免列表出现两个无法区分的条目。
- 文件页顶部操作按钮支持统一折叠。
- 概览页移除「运行诊断」卡片，诊断入口改为「服务端资源」卡片标题栏右上角的三角形感叹号图标（点击进入诊断页）；图标颜色沿用原卡片语义（修复/诊断中、未诊断、通过、有问题），原结论文案转入无障碍描述，「一键安全修复」入口仍在诊断页内。

### 构建与发布

- Release 包固定使用维护者密钥签名：CI 从仓库 Secret 还原 keystore 并校验文件哈希，`signingConfig` 优先读取仓库内 `signing/debug.keystore`。此前 release job 会在构建机上现场 `keytool -genkeypair` 生成随机密钥，产出的包与线上版本签名不一致，用户无法覆盖安装。
- 发布流水线新增产物签名自检：APK 证书 SHA-256 指纹与发布密钥不符时直接终止发布。
- Release 不再上传 `mapping.txt`：R8 混淆对照表体积巨大（本项目约 196 MB，而 APK 仅 14 MB），不适合作为 Release 资产分发。需要还原线上崩溃堆栈时，用同一提交本地执行 `assembleRelease` 重新生成。

### 文档

- `ARCHITECTURE.md`、`README.md`、`README.zh-CN.md` 同步更新：补充运行环境（Java/PHP）、各核心版本源对照表、配置写入竞态处理、崩溃报告提取策略与新核心列表。
- 新增 [`docs/AUTOMATION-TASKS.md`](docs/AUTOMATION-TASKS.md)：全部非用户触发的自动任务梳理，按风险分级列出检测内容、用途、执行时机、触发条件与性能影响。

## 1.2.5

### 服务器事件通知

- 新增服务器事件系统通知：启动完成、异常退出、停止时提醒（默认「仅重要」）。
- 「全部」档位额外通知玩家进出服；通知等级可在设置页切换（关闭/仅重要/全部）。
- 定时开服失败时也会发通知，便于后台及时知晓。

### 自动化

- 支持每日定时开服与定时关服（设置页「自动化」卡），到点自动执行，触发后自动注册次日计划。
- 设备重启后自动恢复定时计划；系统未授予精确闹钟权限时退化为约 ±1 分钟触发。
- 新增「停止时自动备份」开关：服务器停止后自动保存一份世界快照，遵从自动备份保留轮换。

### 基岩版新核心

- 下载页新增 PocketMine-MP 与 Allay 两个基岩版核心，选择按钮样式与 PowerNukkitX 一致（同款微红底色），默认 UDP 端口 19132。
- PocketMine-MP：版本列表来自 pmmp 官方通道（仅 5.x 稳定版），下载后以 `PocketMine-MP.phar` 落盘；首次启动自动下载 PocketMine 专用 PHP 运行环境（Android aarch64 原生构建，含 chunkutils2/encoding/leveldb/pmmpthread 等全部必需扩展，多服务器共享）并以 `php PocketMine-MP.phar --no-wizard` 启动，无需 Java。注意：Termux 自带的 php 缺少必需扩展，无法用于 PocketMine。
- Allay：版本列表来自 GitHub Releases，启动需要 Java 21 及以上（低于 21 自动切换，未安装则明确提示），以 `java -jar` 运行；首次启动前自动生成 `server-settings.yml`，端口随应用配置写入。IPv6 绑定**按设备能力条件处理**（见 `McServerController.kt` 的 `config.ipv6ListenAvailable`）：当前 JVM 确实支持 IPv6 监听时，首次生成配置写 `enablev6: true`，且此后不再改写已有配置，改不改由用户在 Allay 配置里自行决定；只有在**确实绑不上** IPv6 时（Allay 默认同时绑定 `::`，Android/Termux openjdk 会抛 `UnsupportedAddressTypeException` 导致网络接口启动失败）才写 `enablev6: false` 并在每次启动时纠正。

### FTP 文件管理与 SakuraFrp

- 新增「FTP 文件管理」（更多页入口）：局域网内用电脑的文件管理器直接访问手机服务器目录（世界、插件、模组、配置），支持匿名访问、账号密码、只读/可写开关与自定义端口，一键复制 FTP 地址。
- 内网穿透新增 SakuraFrp：填入访问令牌（Token）即可拉取账号下的隧道列表与节点列表，支持一键创建新隧道（选择节点/协议/本地端口），选中隧道后点「启动隧道」自动拉取官方配置并启动 frpc，无需自建服务器。

### 界面改版

- 底部系统导航栏全透明（手势/三键导航均适配，关闭对比度遮罩）。
- 顶部白色标题栏高度增加 10dp；概览页标题栏右侧新增高对比「依赖与环境管理」入口按钮。
- 新增「依赖与环境管理」独立页面（Java 版本 / 依赖 / Termux 环境三模块），每组提供卸载（直接删除文件，带确认弹窗）/ 重装 / 安装 三个操作，状态实时刷新：支持多 Java 版本查看与点选管理目标、新增卸载 Java 与卸载/重装依赖能力（服务器运行中自动禁止破坏性操作）。
- 崩溃日志页检测到 Java 版本问题（未安装/版本不满足）时，列表行与分析弹窗均出现「前往安装」按钮，直达管理页 Java 模块。
- 概览页移除「删除依赖」「重新安装依赖」按钮与「Java 版本管理」大卡片（能力全部收拢到管理页）；保留「安装 Java」「安装 Termux」初始入口，仅在未安装时显示、安装成功后自动隐藏。

### MCP 服务

- 内嵌 Model Context Protocol 服务器（Streamable HTTP）：局域网内的 AI 助手（Claude、ZCode 等 MCP 客户端）可连接手机，远程管理服务器。
- 工具扩展至 57 个：服务器管理（状态、列表、切换活动服务器、启动/停止）、MC 控制台（发送命令、读取最多 2000 行日志）、Termux 运行环境（执行命令并返回输出与退出码，支持超时）、服务器文件管理（列目录、读写、删除、改名、建目录，沙盒限定在服务器目录内）、模组（Modrinth 搜索、安装、已装列表）。
- 多服务器支持：文件、日志、模组与启动工具均接受 `server` 参数按名称/目录名定位，缺省使用活动服务器；`select_server` 切换会持久化。
- 新增 `upload_file`（base64 上传电脑文件到手机服务器目录，大文件分块 append）与 `extract_archive`（解压 zip/tar/tar.gz/tar.bz2/tar.xz，自动跳过路径穿越与符号链接条目），支持从电脑传输模组、存档等；`import_server` 一键将上传的服务端压缩包解压注册为新服务器（自动识别核心与版本，识别不出也按「导入/未知核心」登记）。
- 保活服务默认开启：应用启动 3 秒内自动拉起前台保活服务并注册周期检查（仍可在保活页手动关闭）。
- 文件页顶部快捷操作（教程按钮等）支持统一收起/展开，并记住折叠状态。
- 修复 MT 管理器等外部方式直接复制文件夹到服务器目录后不出现在应用中的问题：进入服务器管理页或启动应用时自动扫描登记；识别不出核心也按「导入/未知核心」登记（降低识别门槛）。
- 服务器管理页新增「修改服务器信息」：编辑显示名称与版本号（仅显示信息，不影响实际版本与核心）。
- 入口在「更多」页新增「MCP 服务」卡片：开关、监听端口（默认 8931）、访问令牌（首次启用自动生成，可随时重新生成）、端点地址与客户端配置 JSON 一键复制。
- 所有请求经 Bearer 令牌鉴权（常量时间比较），默认关闭；端点为无状态实现，`initialize` 版本协商兼容 2024-11-05 / 2025-03-26 / 2025-06-18。

## 1.2.4

### 性能与滚动

- 服务端内存占用进一步降低：移除强制的 `-Xms` 预分配，启动时不再先预留多余的堆内存。
- 概览页改为 LazyColumn 滚动，Header、Hero、Java/诊断/资源/初始化/依赖/核心选择等一级卡片全部拆分为独立列表项，服务器运行前的掉帧显著改善。
- 日志翻译、颜色解析和文件列表扫描移至后台线程，减少主线程负载。

### 日志

- 打开终端页时快速加载 `latest.log` 历史日志（与实时输出自动去重合并）。
- 服务器报错弹窗新增“完整日志”视图：记录服务端自本次启动到停止的全部日志，支持复制与分享。

### 崩溃报告

- 所有非零退出都会弹出错误报告，不再只限于启动 20 秒内（运行中崩溃、exit=137 被系统终止等不再静默）。
- 启动后 5 秒内退出（即使退出码为 0）也按启动失败处理并弹窗，测试核心或配置错误不再无声无息。
- 进程死亡时界面不再被错误覆盖为“运行中”；App 重新打开后 5 分钟内自动补弹最近一次崩溃报告。
- 崩溃报告保存前等待日志刷新完成，报告与完整日志不再丢失末尾几行。

## 1.2.3

### 稳定性与内存

- 修复服务器图标和插件图标页面可能因 Compose 仍在绘制已回收 Bitmap 而闪退的问题。
- 插件图标按实际显示尺寸缩小解码，降低同时加载多个远程图标时的内存占用和垃圾回收卡顿。
- Release 包继续启用 R8 代码混淆和资源压缩，并使用原有签名文件。

## 1.2.2

### 服务器状态

- 修复 CPU 占用率无法正常读取的问题，改为追踪真正的 Java 服务进程，并兼容 shell、PRoot 等多层启动方式。
- 服务器停止后清除旧的 CPU 采样数据，避免下次启动显示错误数值。
- 正版验证开启时，概览页状态卡片显示“正版验证未关闭，离线玩家无法进入”提示。

### 构建与性能

- 完善 LazyColumn 和终端日志列表的稳定键与内容类型，减少快速滑动时的重复重组。
- Release 包继续启用 R8 代码混淆和资源压缩，并使用原有签名文件。

## 1.2.1

### 服务器导入与启动

- 保留服务端入口文件原名，不强制生成或覆盖 `server.jar`。
- 支持文件夹、压缩包、单个 JAR/PHAR 与 Modrinth `.mrpack` 导入；导入失败自动清理半成品。
- 增加导入文件名校验、路径穿越防护、顶层目录处理和 Forge/NeoForge 启动入口适配。
- 修复服务端进程刚启动就被误判为“运行中”的问题；仅在核心输出真实就绪日志后结束启动进度。

### 稳定性与性能

- 改善长日志缓冲、终端显示和页面状态更新，减少长列表渲染与日志追加造成的卡顿。
- 补充核心识别、导入布局、启动阶段、日志缓冲和下载速度相关测试。
- Release 构建启用 R8 代码混淆与资源压缩。

## 1.2.0

### 文档与运行说明

- 更新中英文 README 和架构文档，准确说明无 Root 手机运行链路：Termux bootstrap、Java 17/21/25 主机路径、Java 8 Ubuntu PRoot 路径、ProcessBuilder I/O、网络连接与后台限制。
- 修正支持核心数量为 10 类，并补充 PowerNukkitX、独立导入入口、支持的压缩格式与 Modrinth `.mrpack` 服务端导入边界。
- 补充崩溃报告的全核心原生报告汇总、离线分析、状态悬浮条及 CPU 采样的设备相关限制。

### 功能

- 导入支持文件夹、JAR、ZIP/TAR/TAR.GZ/TAR.XZ/TAR.BZ2/TAR.ZST/TAR.LZ4/7z，以及 Modrinth `.mrpack`；整合包下载文件会校验 SHA-1，并应用服务端覆盖目录。
- 下载页核心选项改为紧凑布局，核心切换会清空旧版本并防止过期请求覆盖新列表，默认显示最近 8 个版本。
- 崩溃报告收集 `latest.log` 最后 200 行和最新原生报告；报告列表可汇总所有已安装核心的原生 `crash-reports/`。
- 增加可选的可拖动运行状态悬浮条；CPU/内存显示在 Android/PRoot 限制下按尽力采样处理。
- Release 构建启用 R8 代码混淆与资源压缩。

## 1.1.7

### 稳定版重发布

- 基于 1.1.6 已验证代码重新构建，仅递增应用版本号，不改变应用功能。
- Release 继续启用 R8 代码混淆与资源压缩。

## 1.1.6

### 终端修复

- 修复终端命令输入框和发送按钮被底部导航栏或软键盘遮挡、可点击但不可见的问题。
- 输入区改为终端页面独立底部栏，输入文字、边框、光标和发送按钮使用固定的深色终端配色。
- 保留多会话、MC 控制台、日志复制、关键日志汉化和日志自动滚动功能。
- 正式包继续启用 R8 代码混淆与资源压缩。

## 1.1.5

### 服务器与导入

- 下载页支持导入服务器文件夹、压缩包及单个 JAR；导入前可修改名称，过程中显示进度并自动识别常见核心和版本。
- 修复 Android 文件夹导入出现 `Invalid URI`、选择后闪退或预填名称失败的问题。
- 补齐 PowerNukkitX 新旧版本源、Legacy 核心启动与 Bedrock 版本展示适配。

### 稳定性与体验

- 修复 APT 依赖安装遇到 `[Y/n]` 时因后台无输入而直接中止的问题。
- 优化终端日志汉化、复制、自动滚动和常用服务器指令。
- 补充启动失败的崩溃报告分析、玩家活动记录和服务器文件编辑能力。
- Release 包开启 R8 代码混淆与资源压缩。

## 1.1.4

### 核心安装与启动

- **关键修复**：NeoForge 自定义版本 `1.21.1` 被误装为 `1.21.11` 的问题。根因是 `resolveNeoForgeUrl` 去掉版本号所有点号后做前缀匹配，`"211"` 同时命中 `21.1.x`（MC 1.21.1）和 `21.11.x`（MC 1.21.11），再经字符串比较选出 `21.11.x` 的 installer。修复为保留末尾点号作为段边界（`"21.1."` 只匹配 `21.1.x`），并用数字段比较取最新 build 号。
- 移除 `MinecraftVersionNormalizer` 中将 `1.21.11` 强制纠正为 `1.21.1` 的错误逻辑——`1.21.11` 是真实存在的 MC 版本，该纠正反而误伤了合法输入。
- NeoForge installer 版本选取改用数字段比较（`21.1.10 > 21.1.9`），避免字符串比较导致 build 号排序错误。
- **修复**：NeoForge/Forge 安装偶现 `java: inaccessible or not found` 错误。根因是 `runInstallerWithRetry` 直接用 `execOnce("java", ...)` 依赖 PATH 查找 java，但 apt post-install 偶尔未创建 `$PREFIX/bin/java` wrapper 脚本。修复为安装前先调用 `ensureJavaReady()` 确保 wrapper 就绪，并用绝对路径执行 installer。
- 终端页面日志自动滚动至底部：旧逻辑以 `visibleLines.size` 作为触发键，缓冲区满后 size 不变导致停止滚动；改为追踪用户滚动状态，新日志到达时若用户未手动上滑则自动跟随。
- 修复下载页日志需先进入终端页才能显示且刷新延迟严重的问题：日志推送循环仅检查 `consoleLines` 订阅数，下载页只订阅 `consolePreviewLines` 导致订阅数为 0、循环跳过更新；改为同时检查两者订阅数。

### UI 修复

- 修复终端输入框突出到导航按钮的问题：`imePadding()` 原先应用在整个 Column 上，键盘弹出时整体布局位移导致输入框被推入底部导航栏区域；改为仅应用在底部输入行上，键盘弹出时输入框独立上移，日志列表通过 `weight(1f)` 自动收缩。

## 1.1.3

### 核心安装与启动

- 完善 Paper、Purpur、Vanilla、Fabric、Forge、NeoForge、Quilt、Velocity、BungeeCord 和 PowerNukkitX 的核心选择、安装、启动、停止与重启流程。
- Forge 1.12.2 使用 Java 8 的 Ubuntu/PRoot 启动路径与 ServerLaunchWrapper classpath 模式。
- 运行时匹配 Java 不兼容日志，识别 class file version、UnsupportedClassVersionError 等特征，并提示核心所需 Java 版本。
- 修复 NeoForge 自定义 Minecraft 版本 `1.21.11` 被误当作有效版本的问题，自动纠正为 `1.21.1`；旧安装记录、下载参数和启动记录一并规范化。
- 启动失败时自动生成并在当前页面展示错误报告摘要；崩溃分析覆盖 Java、模组、插件、端口、EULA、权限、字体、JNA/OSHI、内存和系统终止等常见线索。

### PowerNukkitX

- 增加 PowerNukkitX 独立目录与配置适配：`server.jar`、`pnx.yml`、`server.properties`、`worlds/`、`plugins/`、`players/`、资源包和专属运行数据。
- 配置页按 Java Edition 与 Bedrock Edition 分离，PNX 配置基于 `pnx.yml` 并同步端口兼容配置。
- PNX 在核心列表中以 Bedrock 标识、UDP 端口、推荐 Java 25 和支持的游戏版本突出显示。
- 合并 PowerNukkitX 新旧仓库 Release 查询，增加版本正文/元数据解析和 GitHub 镜像回退。
- PNX 下载增加 JAR 完整性、入口类、Manifest、文件大小和 SHA-256 校验；镜像不支持 Range 时自动降级为单连接下载。

### 备份与数据管理

- 世界备份和完整服务器备份统一保存到 `/storage/emulated/0/世界与服务器的备份/`。
- 支持自定义自动备份类型、分钟级间隔和保留数量；自动备份前执行 `save-all`。
- 使用带秒级时间的新版命名格式，并兼容读取旧版私有目录和公共目录备份。
- 自动清理仅处理当前服务器、当前类型的新版自动备份；手动备份、历史备份和其他服务器备份不会被删除。
- PNX 世界备份使用整个 `worlds/`，完整备份排除日志、锁文件并保留配置、插件、玩家数据等可恢复内容。

### 服务器管理与终端

- 底部导航调整为概览、下载、服务器管理、玩家、文件、终端、设置和更多菜单。
- 模组/插件、服务器配置和服务器图标迁移至服务器管理页；网络、备份、诊断和保活等低频功能收进更多页面。
- 新增多会话 Termux 终端，支持会话新建、关闭、独立输出、命令历史、命令队列和 Minecraft 控制台。
- 终端支持日志复制、长按选择复制、关键日志汉化开关和常用 Minecraft 快捷指令抽屉。
- 修复终端输入区被底部导航遮挡、复制按钮难以点击和小屏误触问题。

### 诊断、文件和玩家

- 新增离线崩溃报告分析器，区分已识别问题、可能原因和日志提醒，并支持复制、分享和查看原文。
- 文件管理支持受路径保护的 UTF-8 文本编辑、JSON 保存校验、原子保存、刷新、复制和未保存修改提示。
- 玩家活动记录按服务器隔离保存，记录进出服、会话、中断状态和在线时长，支持搜索与 CSV 导出。
- 运行诊断改为展示可验证的服务端数据，修复 Java TCP 与 PNX UDP 端口检测误报。
- 概览页首次进入后台刷新服务器地址和网络状态，无需先打开网络页面。

### 更新与性能

- 重做应用更新流程：支持 VPN 环境下版本检测、镜像回退、下载完成后的系统安装器跳转，并提供项目 GitHub 页面入口。
- 更新弹窗增加“本次推送不更新”按钮，并明确提示更新可能影响或丢失服务器核心与世界，用户应先备份。
- Java 安装源优先使用国内 APT 镜像；Java 8 安装失败时不会错误地自动串行安装 Java 17、21、25。
- 概览页使用“更多管理”折叠区收纳低频维护项，首次默认收起并持久化用户选择。
- 优化 Compose 页面间距、触控区域、列表密度和后台文件 I/O，减少启动卡顿与滑动掉帧。
- Release 构建开启 R8 代码混淆和资源压缩。

