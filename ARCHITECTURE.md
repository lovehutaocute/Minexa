# Minexa 技术架构

## 范围

Minexa 在无 Root 的 Android 设备上运行 Minecraft 服务端进程。它不是内核容器，也不会修改 Android 系统权限：运行时、Java 包、世界和服务端进程均归应用沙盒所有。

## 运行时与进程链路

```text
Compose UI
  -> McViewModel / Repository (StateFlow + DataStore)
  -> McServerController (下载、安装、启动参数)
  -> TermuxRuntime (环境、进程 I/O、文件)
  -> /system/bin/sh -c "... exec java ..."
  -> Minecraft 服务端子进程
       -> stdin: 控制台命令
       -> stdout/stderr: console flow + logs/latest.log
```

`BootstrapInstaller` 在首次初始化时下载并校验 Termux bootstrap rootfs。`CommandExecutor` 提供 Termux 兼容环境，包括 `PATH`、`LD_LIBRARY_PATH`、`PREFIX`、`HOME` 和临时目录。应用使用 Android `ProcessBuilder`，不依赖 tmux。

### 四条并存的运行时链路

| 链路 | 承载 | 启动方式 |
|---|---|---|
| A. Termux 宿主 JVM | Java 11 / 17 / 21 / 25 | `sh -c "... exec java ..."` |
| B. PRoot 来宾 JVM | Java 8 / 11 / 17 / 21 | `sh -c "... exec proot ..."`，来宾内 `exec /usr/lib/jvm/.../bin/java` |
| C. FCL 原生 JRE | Java 17 / 21 / 25 | 直接执行解压出的 Android 原生 `java` |
| D. PocketMine-MP | 无 JVM | 下载 aarch64 原生 PHP 后 `php PocketMine-MP.phar` |

链路 A 与 B 的分派由**唯一**谓词 `isProotGuestRuntime(vendor, version)` 决定：`Proot → true`；`Termux → version == Java8`；`Fcl → false`。所有 `when (javaVersion)` 分支都应查这一个谓词，不要在各处重新判断。

**Java 8 只出现在「PRoot 容器」分组下。** 它本来就走链路 B，早先挂在 Termux 分组里，导致同一个容器在两处列出：用户会以为有两份而重复安装，或看不懂磁盘占用为何翻倍。因此 `JavaVendor.Termux.versions` **刻意不含 Java 8**（见 `JavaRuntimeCatalogTest.java8IsOfferedExactlyOnceAcrossAllVendors`）。

`isProotGuestRuntime` 仍保留 `Termux → Java8` 这一支：它**不是**为了展示，而是为了让历史配置继续可用。老配置里 Java 8 存在 Termux 名下，读盘时由 `McConfig.normalizeProotRuntime()`（在 `ServerRepository.decodeConfigLenient` 里，与既有的 `Stun → Frp` 迁移同一处）改写为 `Proot + Java8` 并把 `prootDistro` 对齐到 `LegacyJava8Distro`（focal）。不归一的话，启动抽屉会显示「发行版 jammy + 版本 Java8」这种页面内自相矛盾的组合 —— jammy 不提供 Java 8，版本一栏会是空白。

归一是**读盘时**做的（`decodeConfigLenient` 是磁盘 → 内存的唯一入口，`configFlow` 与 `ensureConfigLoaded` 都经过它），因此界面显示的发行版与实际启动用的发行版不会分叉。归一不改变实际容器：归一前后都是 focal + Java 8，已装环境不受影响，也不需要重新安装。这条由 `JavaRuntimeCatalogTest` 的归一测试组钉住（合法性、指向同一容器、不误伤其他组合、幂等）。

`legacyJava8Profile` 的发行版取自 `LegacyJava8Distro` 这**同一个常量**，两处若各写一份 focal，改了一处就会出现「归一后配置指向 A，实际启动找 B」的错位。

### PRoot 容器（链路 B）

PRoot 是纯用户态兼容层（`ptrace` + 路径重写），`--change-id=0:0` 只是**假 uid 0**，**不提供真实 Root**。

关键 bind：`--bind=<宿主 serverDir>:/srv/mineserve`。服务端目录因此以 `/srv/mineserve` 出现在来宾里，世界 / 插件 / 备份 / FTP / 文件管理全部仍在原宿主路径上，应用其余部分无需改动。

`ProotContainerProfile`（`data/ProotContainer.kt`）是「发行版 × Java 版本」的**路径唯一真相来源**：rootfs 目录、就绪标记、proot 临时目录、来宾 `JAVA_HOME`、apt 包名、`java` 与 `libjvm.so` 的候选路径都由它推导，不在别处硬编码。

版本矩阵的唯一真相来源是 `ProotDistro.availableJavaVersions`：

| 发行版 | apt codename | 可安装 Java |
|---|---|---|
| Ubuntu 20.04 (focal) | `focal` | 8 / 11 / 17 |
| Ubuntu 22.04 (jammy) | `jammy` | 11 / 17 / 21 |

`JavaVendor.Proot.versions` 是两者的**并集**（8/11/17/21）。两处 UI 对这份并集的用法**刻意不同**：

- **依赖管理页**（`JavaRuntimeGroupList`）列出并集，把当前发行版装不上的版本**置灰**（`onClick` 里 `!unavailable` 才回调）。置灰同时满足「看得到」与「点不动」—— 只列可用版本会让人以为应用漏了某个版本，全列可点又会让人点到装不上的项。
- **启动抽屉的下拉框**只列 `prootDistro.availableJavaVersions`，因为发行版选择就在正上方，过滤掉装不上的版本比列出来再置灰更直接。

**没有任何发行版提供 Java 25** —— 这是 Ubuntu arm64 源的事实，提示语固定为「该容器不提供」，不是「暂未支持」。

`prootProfileFor` 对不可安装的组合返回 `null`（**绝不静默替换成别的版本**），调用方必须给出明确错误。

### 自动匹配 Java 版本：来源不可被换掉

启动服务端前有一套「按 MC 版本自动匹配 Java」的逻辑（`McServerController.launchMc`）：当前版本不够新就自动升级，Forge ≤1.16 则降到 Java 8/11，Allay 强制 21+。

**这套逻辑只允许在用户所选来源内调整版本，绝不更换来源。**

早先的实现会在所选来源不提供目标版本时，回退到「第一个提供该版本的来源」。后果：用户在「运行环境」里明确选了 PRoot 容器，启动 Paper 26.1（需要 Java 25）时被静默改成 **Termux + Java 25**，而且这个改动会 `updateAndSaveConfig` 落盘 —— 用户的显式选择被永久覆盖，下次启动直接换了链路。

这不是「自动修复」：运行环境是用户唯一能指定「用哪个 libc 世界跑服务端」的入口，PRoot 的存在意义正是脱离 bionic。替用户换掉它等于换掉整个运行前提。而且这条路径**会静默成功**（Termux 确实提供 Java 25），不像失败的替换那样会暴露出来。

现在由 `requireVersionInVendor(version, reason)` 统一处理：来源提供不了就抛错，并把「换来源还是换版本」的决定权交还用户。判断「能不能装」必须走 `JavaVendor.provides(version, distro)` / `availableVersions(distro)` —— **不能只用 `vendor.versions`**，那是两个发行版的并集，会把 focal 装不上的 Java 21 判成可用。

错误话术会尽量给到底：若换个发行版就能装（例如 focal 缺 Java 21 而 jammy 有），直接说明「把 Ubuntu 20.04 切换到 Ubuntu 22.04 即可」。`JavaVendorStickinessTest` 钉住这条策略的前提（哪些版本任何发行版都不提供、FCL 确实一个 8/11 都没有）。

### 依赖管理页的 Java 分组：折叠 + 发行版胶囊

三个来源各是一段「标题 + 说明」的可折叠分组，**默认全部折叠**。折叠状态由 `EnvManagerScreen` 的 `expandedVendors`（`Set<JavaVendor>`）持有 —— 这是纯界面状态，不进 ViewModel。

折叠的理由是行数：三个来源共 11 条版本行，全展开会把下面「卸载 / 重装 / 安装」三键顶出屏幕，而这三个按钮才是本板块的主要操作。

代价是选中项看不见了，而那三个按钮作用于当前选中项。因此**折叠状态下**在分组说明行末尾追加「· 已选 Java 17」（`java_group_selected`），只在该来源持有选中项时出现；展开时不显示 —— 选中的那一行有 Mint 高亮边框，已经足够。

PRoot 分组内部额外有一个 **Ubuntu 发行版胶囊切换控件**（`ProotDistroPillSwitcher`）：左 Ubuntu 20.04、右 Ubuntu 22.04，选中项用 Indigo 胶囊背景标出，切换时蓝色背景用 `animateDpAsState`（260ms, `FastOutSlowInEasing`）滑动到另一侧。

两个选项等宽，所以高亮块宽度就是轨道的一半，位移按索引线性算，不需要测量文字宽度。胶囊里用 `ProotDistro.shortName`（「Ubuntu 20.04」）而非 `displayName` —— 后者带代号括号，在半个屏宽里会挤成两行。

切换发行版调用 `McViewModel.applyRuntimeSelection(..., prootDistro = picked)`，**与版本在同一次 `cfg.copy` 里写入**：两个发行版的可用版本不同，分两次写会撞上 config 落盘的 300ms debounce，中间态还可能写出该发行版装不上的组合。

### 运行环境选择器（启动抽屉）

抽屉里的「运行环境」下拉框是**四个**互斥选项，而不是「Java/PHP 两个 + 来源在别处选」：

| 选项 | 写入的 `runtimeKind` / 来源 |
|---|---|
| Termux Java | `Java` / `JavaVendor.Termux` |
| Pojav JRE | `Java` / `JavaVendor.Fcl` |
| PRoot Linux 容器 | `Java` / `JavaVendor.Proot` |
| PHP | `Php` |

**PRoot 必须在这一层独立成项**：`runtimeKind` 只区分 Java/PHP，来源若不在这个下拉里选，`selectedJavaVendor = Proot` 的配置打开抽屉就会显示「Termux Java」被选中，而版本列表又是 Termux 的那一份 —— 界面显示的来源与实际运行的来源不一致。

选中「PRoot Linux 容器」时，下方多出一行**发行版**选择，且它排在**版本之前**：发行版决定版本列表，顺序反了会让人以为「换了发行版版本却没变」是 bug。更新发行版时走 `applyRuntimeSelection(..., prootDistro = ...)`，把发行版与版本**一次写入**并按新发行版重新对齐版本（focal↔jammy 的可用版本不同，沿用旧版本会写出装不上的组合）；分两次写会撞上 config 落盘的 300 ms debounce（与 `applyRuntimeSelection` 文档里记录的那个竞态同源）。

历史配置的 `Termux + Java8` 在读盘时已被归一为 `Proot + Java8 + focal`（见上文），因此这里 `envKey` 不需要为它保留特例分支。`envKey` 里仍留了一支 `isProotGuestRuntime` 判断作为兜底，指向同一项。

### 进程归属与孤儿清理

PRoot 链路下注册为 `mcProcess` 的是 **proot 追踪器本身**，真实 java 服务端是它的**子进程且 pid 不同**。这带来两个后果：

1. `findMcChildPid` / `findServerPid` 同时接受宿主 `serverDir` 与来宾 `/srv/mineserve` 两种路径形式。
2. `killOrphanMcProcess` 必须同时排除「被追踪进程的整个后代集合」与「被追踪进程自身」，否则会把健康的服务端当成孤儿杀掉。

`matchesManagedServer(cmdline, serverDir)` 抽在 `runtime/OrphanProcessMatch.kt`。**已知局限**：来宾路径 `/srv/mineserve` 对每个服务端实例都相同，无法据此区分实例；因此当未指定目标目录而又有正在追踪的服务端时，函数会跳过清理（而非猜测），宁可留下一个孤儿进程也不误杀。

IPv6 监听能力（`McConfig.ipv6ListenAvailable`）按来源判定，且**必须先判是否走来宾容器**：FCL 自带支持；Termux + `PatchedJdk` 靠按 bionic 构建的 `libipv6fix` 兼容层；而**来宾内是 glibc loader，加载不了该兼容层**，因此 PRoot 与 Termux + Java 8 一律为 `false`。这个判定会直接决定 Allay 的 `enablev6` 写成什么，判错会让服务端启动即抛 `UnsupportedAddressTypeException`。

PocketMine-MP 不经过 JVM：首次使用时下载 Android aarch64 原生 PHP 构建（含 `chunkutils2` / `encoding` / `leveldb` / `pmmpthread` 等必需扩展）到 `home/php-pmmp/`，所有 PocketMine 服务端共享同一份运行时，随后以 `php PocketMine-MP.phar` 启动。Termux 自带的 `php` 缺少上述扩展，不能用于 PocketMine。

运行环境由用户在界面上选择（`RuntimeKind.Java` / `RuntimeKind.Php`），选择完全生效，不会被核心类型反向改写；启动前只做一致性校验（PocketMine-MP 需要 PHP，其余核心需要 Java），不匹配时给出明确错误而不擅自更改用户选择。

## 存储布局

实际 Android 包路径会因安装而变化；以下路径相对 app `filesDir`：

```text
home/
  bin/, lib/, etc/                 Termux 兼容运行时
  home/
    servers/{dirName}/             一个隔离的服务端
      server.jar, eula.txt, server.properties
      plugins/, mods/, worlds/, logs/latest.log
    snapshots/                     备份归档
    crash-logs/                    应用生成的崩溃报告
  php-pmmp/                      PocketMine-MP 专用 PHP 运行时（多服务端共享）
  jvm-fcl/                       FCL 原生 JRE（Java 17 / 21 / 25）
  var/lib/mineserve/
    focal-rootfs/                Ubuntu 20.04 来宾 rootfs（Java 8 只在这里）
    jammy-rootfs/                Ubuntu 22.04 来宾 rootfs
    <distro>.ready               该发行版容器「rootfs + apt 可用」的就绪标记
tmp/
  proot-<distro>/                PROOT_TMP_DIR（guest 路径重写用的 glue 文件）
  <distro>-resolv.conf           来宾 DNS 配置（宿主 /etc/resolv.conf 不可读）
runtime/, native/                 bootstrap/运行时工作目录
```

容器路径**只按发行版命名，不编码 Java 版本**：同一个 rootfs 本就能装任意受支持的 JDK，因此 focal 装完 Java 8 再装 Java 17 不必重下 rootfs。就绪标记的含义是「该容器本身已装好（rootfs 解压完成 + apt 可用）」，不是「里面装了哪个 Java」—— 某个 Java 版本是否可用由 `isContainerJavaReady(profile)` 逐版本判断。

`TermuxRuntime.serverDirFor(dirName)` 是服务端目录的共同边界。服务端导入和解压通过规范路径校验限制写入范围；TAR 的符号链接和硬链接会被忽略，防止压缩包逃逸。

## 服务端生命周期

`McServerController` 解析所选核心和启动参数，再调用 `TermuxRuntime.startMc`。shell 通过 `exec` 被 Java 替换，便于观察进程归属和退出。`TermuxRuntime` 保留进程 stdin，异步消费合并后的输出，写入 `logs/latest.log`，并在进程退出时调用退出处理。控制器可记录异常退出并应用配置的自动重启策略。

可运行或识别的具体核心为 Paper、Purpur、Leaves、Leaf、Spigot、CraftBukkit、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord、PowerNukkitX、PocketMine-MP 和 Allay。Forge、NeoForge、Quilt 使用各自的 installer 启动准备流程。PowerNukkitX、PocketMine-MP、Allay 是 Bedrock 服务端，使用 UDP 网络并以 19132 为默认端口，不是 Java Edition 服务端；其中 PowerNukkitX 与 Allay 仍由 JVM 承载，PocketMine-MP 由 PHP 承载。

## 容器与运行环境的增删

### 安装

`installProotJava(profile)` 一次性完成「解压 rootfs → 写 apt 源 → apt 安装 JDK → 校验」：

1. `installUbuntuBaseRootfs(profile)` 下载并解压 Ubuntu Base rootfs，成功后写就绪标记。
2. `prepareUbuntuDns()` 把宿主 `/etc/resolv.conf` 复制到来宾可读的位置 —— 容器内没有它 apt 无法解析域名。
3. 容器内 `apt-get install <profile.aptPackage>`。
4. 校验 `java -version` 的输出，**逐版本用各自的正则**（Java 8 输出 `1.8.0_xxx`，不能用 `8\.` 去匹配）。

rootfs 解压与 apt 安装都是长任务，中途断电会留下半成品，因此安装入口会先尝试**恢复未完成的事务**再继续，而不是直接覆盖。

### 卸载：两个粒度，入口分开

| 操作 | 语义 | 实现 |
|---|---|---|
| 卸载某个 Java 版本 | 只清掉这一版，容器与其余版本保留 | 容器内 `apt-get purge <pkg>`（`uninstallProotJava`） |
| 删除容器 | 回收整个发行版容器（含其中所有 Java 版本） | 直接删 rootfs（`deleteProotContainer`） |

两者粒度不同，因此**入口也分开**，并各自有明确的确认文案。若混成一个按钮，用户点「卸载 Java 17」会把 Java 8/11 一起清掉，属于静默数据丢失。

apt 卸载的几个刻意选择：

- **不做 `autoremove`**。多版本共存时它可能拔掉其他 JDK 仍需要的公共依赖（`ca-certificates` / `fontconfig`），问题要到下次启动才暴露。只 purge 目标包。
- **先恢复 dpkg 事务**（`dpkg --configure -a`、`apt-get -f install`）。容器内 apt 因网络中断停在半途是常见的，直接 purge 会卡在锁或未配置包上。
- **卸载后校验所有已知 JRE 布局**。Java 8 的 Ubuntu 包把 JRE 放在 `jre/bin/java` 下，只查新 layout 会把「其实还在」判成已卸载。校验不通过则整个操作报告失败。
- **失败时不改动任何宿主文件**。容器内状态未知时贸然删标记，会让用户看到「未安装」而磁盘上仍留着完整 JDK。

`deleteJava` 是 `suspend` 的：PRoot 来源要进容器跑 apt（秒级到十秒级），必须挂起。其余来源是本地文件操作，但走同一条签名 —— 为它们在调用侧分叉出两条路径，只会让「忘了加运行中守卫」这类漏洞更容易出现。

所有删除入口都先检查 `isMcRunning()`，正在使用的 rootfs 被删掉会让服务端以难以定位的方式崩溃。

### 安装进度

`installJava(..., onProgress)` 的 0–100 在三种来源下含义不同：

| 来源 | 进度来源 |
|---|---|
| FCL | 按下载字节数实时换算 |
| PRoot | **按阶段**：rootfs 下载解压 0–60，容器内 apt 安装 60–100 |
| Termux 宿主 | apt 无细粒度进度，只在开始/结束时回调 |

PRoot 的 rootfs 阶段内部再按「下载 85% / 解压修复 15%」细分，贴近真实耗时占比。之所以要给 apt 段也报两个点：整段是数分钟的长任务，`CircularProgressIndicator` 长时间不动会被当成卡死。

### 诊断页的分层报告

诊断页由 `buildDiagnosticReport()` 产出、`DiagnosticsScreen` 纯展示（`report.checks` 数据驱动，UI 不知道任何来源细节）。与容器相关的检查有两条：

- **`java`**：走 `javaRuntimeDiagnostic(vendor, version)`，回答「所选这一版能不能用」。
- **`proot-<Distro>`**（每个发行版一条）：走 `prootContainerState(distro)`，把容器拆成四层报：rootfs 是否存在、shell 是否可用、就绪标记、已装版本列表。**只报一句「未安装」会把两种代价完全不同的情况混为一谈** —— rootfs 缺失要重跑几百兆下载，而「容器好了但没装 Java」只是一次 apt 安装。仅「解压不完整」标为可一键修复；缺 Java 属于用户选择。

另外两条检查的**必需性判定必须覆盖全部来宾链路**，两处都曾因为只看 `Java8` 而出错：

- `prootRequired` 必须由 `isProotGuestRuntime(vendor, version)` 决定。早先只判 `Termux && Java8`，选中 PRoot 来源时诊断会报告「无需 proot」并标为 NotApplicable —— 而 proot 正是这条链路赖以启动的东西。
- 字体检查必须按来源分流（`fontRuntimeDiagnostic(vendor, version)`）。宿主侧 `fontRuntimeReady(version)` 只对宿主链路有意义，因此加了 `require` 挡住误用；来宾链路的字体在容器内，拿宿主 `etc/fonts/fonts.conf` 去回答会误报「已就绪」。

## 下载与导入

下载页通过 `McServerController` 请求核心版本源。请求令牌会阻止旧网络响应在用户切换核心后覆盖新版本列表；界面默认显示最近 8 个版本，并可展开全部结果。

绝大多数核心的版本列表来自官方接口，不做本地硬编码：

| 核心 | 版本源 |
|---|---|
| Paper | `fill.papermc.io/v3/projects/paper` |
| Purpur | `api.purpurmc.org/v2/purpur` |
| Leaves / Leaf | `api.leavesmc.org` / `api.leafmc.one` |
| Vanilla | `piston-meta.mojang.com/mc/game/version_manifest_v2.json` |
| Fabric | `meta.fabricmc.net/v2/versions/game`（仅 `stable`） |
| Quilt | `meta.quiltmc.org/v3/versions/game`（仅 `stable`） |
| Forge | `files.minecraftforge.net/.../promotions_slim.json`（键名去 `-recommended`/`-latest` 后缀） |
| NeoForge | `maven.neoforged.net/.../maven-metadata.xml`（由版本号反推 MC 版本，排除 `-beta`/`-rc`） |
| Velocity | `fill.papermc.io/v3/projects/velocity` |
| PocketMine-MP | `raw.githubusercontent.com/pmmp/update.pmmp.io` 通道 JSON |
| Allay / PowerNukkitX | GitHub Releases API |

三个例外，它们**没有**上游版本接口可查，只能使用内置静态列表：

| 核心 | 原因 |
|---|---|
| Spigot | SpigotMC 不提供公开版本 API |
| CraftBukkit | `cdn.getbukkit.org` 只有固定下载路径，无版本清单接口 |
| BungeeCord | 官方 CI 只提供 `lastSuccessfulBuild` 单一构建，没有版本列表（因此版本列表固定为 `latest`） |

PowerNukkitX 另有一条 Maven Central 回退路径：它的历史版本（`PowerNukkitX-Legacy`）没有 GitHub Releases，只有 Maven 产物，因此版本列表由两个仓库的 Releases 与 Maven 目录合并而成。

所有请求均以 `runCatching` 包裹并在失败时回退到内置静态列表，断网不会导致列表空白。版本排序使用 `mcVersionComparator` 按数字段比较，正确处理 `26.2 > 1.21.11 > 1.21.4` 这类跨编号体系的情况。

下载地址由 `resolveDownloadUrl` 按核心分派，部分核心附带国内镜像：Vanilla 走 BMCLAPI，Forge 走 BMCLAPI 的 installer 接口，Fabric 走 BMCLAPI 的 fabric-meta 镜像；PowerNukkitX 额外提供多个 GitHub 代理。Forge 的 installer 若在所选 MC 版本上没有对应构建，NeoForge 的版本无法匹配时，都会直接抛出明确错误，不会回退下载一个不相干的版本。

`ServerImporter` 提供互相独立的文件夹、压缩包、JAR 与 Modrinth 整合包入口：

- 压缩包支持 ZIP、TAR、TAR.GZ/TGZ、TAR.XZ/TXZ、TAR.BZ2/TBZ2、TAR.ZST/TZST、TAR.LZ4 和 7z；符合条件时会剥离一层外包装目录。
- JAR、文件夹和压缩包导入通过 `ServerCoreDetector` 根据布局、Manifest 与已知入口类推断核心和版本。
- Modrinth `.mrpack` 导入读取 `modrinth.index.json`，下载服务端必需文件，在提供时校验 SHA-1，跳过 `env.server=unsupported`，并应用 `overrides/` 和 `server-overrides/`。

CurseForge 整合包没有实现直接导入，因为它的元数据/API 流程并不等同于自包含的 `.mrpack`。

## 后台运行与状态

`McForegroundService` 管理前台通知、可选 CPU/Wi-Fi 唤醒锁和可选的可拖动状态悬浮条。服务返回 `START_STICKY`；`BootReceiver`、周期 WorkManager 和 `onTaskRemoved` 的非精确闹钟尝试提供额外恢复路径。这些都只能尽力保活，无法覆盖 Android/厂商的省电策略。

悬浮条从仓库状态获取进程 CPU 和内存。CPU 采样会在系统允许时跟踪子进程；Android 沙盒和 PRoot 对 `/proc` 的限制可能使该值不可用。

Java 与 PHP 运行时的安装状态都通过探测可执行文件是否存在且具备可执行位来判定（`isJavaInstalled` / `isPhpInstalled`），修复与安装流程结束后统一刷新，避免 UI 出现"已安装"与"不可用"不一致。

## 配置写入与并发

所有配置写入都经 `ServerRepository.updateConfig` / `updateAndSaveConfig`，两者都把「读改写」放进 `MutableStateFlow.updateAndGet`，基准是仓库内的**权威内存态**，因此并发调用不会互相覆盖，也不依赖任何时间窗口假设。

落盘由 `Channel.CONFLATED` 加 300ms debounce 完成，只保留最后一份。这对配置是安全的——因为每次入队的都是一份**完整**的 `McConfig`（由权威态原子算出），丢弃中间态只等价于「跳过若干次落盘」，最终态永远正确。反过来说：**绝不能把「部分字段的补丁」投进这个通道**。历史上确实有过两次误用：`McViewModel.updateConfig` 曾维护 `pendingConfig` + `pendingUntilMs`（800ms，略大于 debounce）窗口来猜「上次修改是否还在窗口内」；部分调用方则传入从 UI 侧取的陈旧快照。两者都会把并发期间的修改整体覆盖掉，现已统一为原子读改写。涉及运行环境这类要同时写多个字段的场景，走 `applyRuntimeSelection` 合并为单次 `copy`。

磁盘上已有的配置若解析失败（断电写坏、字段类型变更），`ensureConfigLoaded` 会**归档原文件并置位 `configDecodeFailed`**，此后一律拒绝落盘：宁可这次修改不保存，也不能拿「解析失败后回落的默认值」把用户设置永久覆盖掉。落盘自身抛出的异常在收集协程内被捕获（内存态保留、下次重试），不会升级为进程崩溃。

## 日志、崩溃与网络

终端输出作为 Flow 共享给 Compose 控制台，并保存到 `logs/latest.log`。发生异常退出时，`CrashReportManager` 生成应用报告：先按关键词（Watchdog、`Current Thread: Server thread`、`Exception in thread`、`Caused by:`、`OutOfMemoryError` 等）成块提取关键片段并保留其后的缩进栈行，再补上最近 800 行日志作为上下文，两者按原顺序去重输出。这样 Paper Watchdog 转储的数百行线程栈不会被单纯截尾丢掉。界面会扫描所有已安装核心的原生 `crash-reports/`，并由 `CrashReportAnalyzer` 在本地执行启发式分析；不会上传崩溃报告。

Minecraft 通过手机的普通网络套接字监听。局域网连接使用手机 LAN IP 和服务端口。隧道侧支持自建 frp / bore 与托管平台 SakuraFrp / ME Frp，四者仅作为隧道客户端；公网可达性仍取决于所选隧道或用户配置的网络映射，应用不会绕过运营商 CGNAT 或 NAT。

## 关键类

| 类 | 职责 |
|---|---|
| `McApplication` | 应用级运行时、仓库、bootstrap 状态 |
| `McViewModel` | UI 状态与功能协调；配置写入统一走仓库的原子读改写 |
| `ServerRepository` | 权威配置内存态、原子写入与 DataStore 落盘、服务器状态 |
| `BootstrapInstaller` | bootstrap 下载、校验和解压 |
| `CommandExecutor` | shell 环境与命令输出 Flow |
| `TermuxRuntime` | Java/PRoot/PHP 启动、进程 I/O、运行时文件 |
| `McServerController` | 核心版本、下载、installer、启动 |
| `ProotDistro` / `ProotContainerProfile` | PRoot 发行版目录与「发行版 × Java 版本」画像（路径唯一真相来源） |
| `JavaVendor` / `JavaRuntime` | Java 来源分类（Termux / Fcl / Proot）与「来源 + 版本」坐标 |
| `isProotGuestRuntime` / `LegacyJava8Distro` / `normalizeProotRuntime` | 「这个组合跑在哪个容器」的唯一谓词、遗留 Java 8 的发行版常量、历史配置归一 |
| `OrphanProcessMatch` | PRoot 链路下的孤儿进程 cmdline 匹配 |
| `FclJrePackages` / `FclJreInstaller` | FCL 原生 JRE 的包元数据与下载解压 |
| `RuntimeKind` / `PhpVersion` / `JavaVersion` | 运行环境（Java/PHP）、PHP 与 Java 版本目录 |
| `MinecraftVersionNormalizer` | MC 版本字符串规范化（当前仅 trim） |
| `ServerImporter` / `ServerCoreDetector` | 服务端/整合包导入和识别 |
| `Worlds` / `BackupManager` | 世界识别/导入导出、世界与整服备份还原 |
| `PluginManager` | 插件与模组扫描、启停、安装、删除 |
| `CrashReportManager` / `CrashReportAnalyzer` | 崩溃捕获、汇总、本地诊断 |
| `McForegroundService` / `StatusOverlay` | 前台寿命、唤醒锁、状态显示 |
| `McpServerManager` / `McpToolCatalog` | 内嵌 MCP 服务器与工具清单 |
| `TunnelManager` | frp、bore、SakuraFrp、ME Frp 四家隧道后端的分派 |
| `WidgetUpdater` / `WidgetActionReceiver` | 桌面组件刷新与组件按钮动作分发 |
| `ScheduleManager` | 每日定时开/停服闹钟 |
