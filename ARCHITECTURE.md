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

### 服务端目录：只有一个根，旧目录靠迁移

服务端**只**存在于统一存储目录（实现见 `data/ServerStorage.kt`）：

| 位置 | 路径 | 说明 |
|---|---|---|
| `Internal` | `/storage/emulated/0/Minexa/Minecraftserver/{dirName}/` | 唯一落点与唯一运行位置 |
| `Legacy` | `<filesDir>/home/home/servers/{dirName}/` | 旧版目录，**只作为迁移源**存在，不参与定位 |

`StorageLocation.Legacy` 只为「读得懂老配置」而保留：这种记录在列表里不显示、不可启动
（`McServerController.start` 会明确拒绝并指向迁移入口），迁移完成后由迁移器改写为 `Internal`。
`ServerStorage.normalizeCores()` 只做一件自动纠正：某条 Legacy 记录的目录**已经**出现在统一目录里
且非空（用户手工搬过或迁移刚完成）→ 就地改 Internal。其余一律等用户确认。

旧目录路径是 `<filesDir>/home/home/servers`，**不是** `<filesDir>/home/servers`：旧代码里
`BootstrapInstaller.rootDir` 本身已经是 `filesDir/home`，`serversDir` 又拼了一段 `home/servers`。
写成单层 `home` 会导致「私有目录读不到、老服务器被当成不存在、还在新目录里凭空建出空文件夹、
启动报找不到 jar」——`ServerStorage.legacyRootCandidates()` 先试真实历史路径，再留一个单层兜底。

### 容器探针与权限修复

- `runUbuntu` 之前，容器自检（`test -x /bin/sh`）的时限是 60 秒，真机上太紧：
  刚解压出来的 rootfs 有上万个小文件，proot 首次进入要逐个处理 `--link2symlink` 与 ptrace 初始化，
  慢设备上超过一分钟很常见。超时就会把**刚装好的** rootfs 判为不可用 → 重建 → 再超时，
  表现正是「进度条停在部署阶段不动」。现在 `ROOTFS_PROBE_TIMEOUT_MS = 180s`，且超时文案单独区分（exit=124）。
- 已有可用 rootfs 时才做自检；rootfs 根本不存在时直接进安装流程，不再白等一个超时。
- `repairUbuntuRootfs` 的 `chmod -R 755` 会遍历上万个文件，而它挂在「自检 / 实测 box64 / 启动服务端」
  三条路径上。现在修完写 `rootfs/.mineserve-exec-repaired` 标记，之后直接跳过 —— 不再让每次启动多花几十秒。

### 迁移（`server/LegacyServerMigration.kt`）

每次冷启动扫描旧目录，**有内容就弹框询问**（不做「不再提示」的持久化标记：用户要求
「二次启动后依旧提示」）。确认后逐个目录执行：

1. 复制到同文件系统下的 `{dirName}.migrating-{ts}`；
2. 比对文件数与总字节数，不一致立即失败并**保留源目录**；
3. `rename` 到最终目录名（同根内 rename 原子）；目标重名时用 `{name}_migrated[_2]`，
   若目标只是空壳（旧 bug 留下的幽灵目录）则清掉后复用原名；
4. **最后**删源目录；全部成功且旧根为空时连根一起删。

结果写回配置（`applyOutcomes`）：已登记核心更新 `dirName` + `location`，未登记的按检测结果新增；
当前选中核心若被改名，`activeCoreName` 跟着改。

### 权限

`StorageAccess` 是唯一判据，且判据是**能不能写**而不是权限位：`targetSdk=28` 在 Android 11+ 仍走旧存储模型，部分设备不给「所有文件访问」也能写公共存储；只看权限位会把这些设备误判成未授权。权限位只用于决定要不要提示。

- 冷启动 → `StorageAccess.consumeAutoPrompt()` 每次进程启动最多弹一次（拒绝后下次启动再弹，同一次运行不连环弹）；
- Android 10- → 运行时 `WRITE_EXTERNAL_STORAGE`；Android 11+ → 只能跳设置页，失败退回应用详情页；
- 未授权时下载/导入在入口处 `ServerStorage.requireInternalWritable()` 直接失败并给可照做的提示，不做半个目录。

### MTDataFilesProvider

`bin.mt.file.content.MTDataFilesProvider`（第三方 DocumentsProvider，随 Manifest 注册，authority `${applicationId}.MTDataFilesProvider`）把 `/data/user/0/<pkg>` 映射成 `content://`，供**外部文件管理器**浏览。

关键事实：它声明了 `android:permission="android.permission.MANAGE_DOCUMENTS"`（签名级），普通应用拿不到，**包括本应用自己**。而本应用读自己的私有目录本来就不需要它（同 uid）。`LegacyPrivateStore` 因此先试 provider（真发一次 query），失败回落同 uid 直连，界面如实显示通道。

`ProviderHealth` 每次回前台体检（能否解析 / enabled / exported / uid 一致），把「上次正常版本 + 当前版本」落盘；升级后突然不正常时说明是升级引起的并给恢复入口。provider 自身无跨版本缓存（`attachInfo` 现算），只要 applicationId 不变、声明不被裁掉，升级不会让它失效。

### 文件系统布局

实际 Android 包路径会因安装而变化；以下路径相对 app `filesDir`：

```text
home/
  bin/, lib/, etc/                 Termux 兼容运行时
  home/
    servers/{dirName}/             仅旧版本遗留（Legacy）；新服务器在公共存储
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

可运行或识别的具体核心为 Paper、Purpur、Leaves、Leaf、Spigot、CraftBukkit、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord、PowerNukkitX、PocketMine-MP 和 Allay。Forge、NeoForge、Quilt 使用各自的 installer 启动准备流程。PowerNukkitX、PocketMine-MP、Allay、BDS 是 Bedrock 服务端，使用 UDP 网络并以 19132 为默认端口，不是 Java Edition 服务端；其中 PowerNukkitX 与 Allay 仍由 JVM 承载，PocketMine-MP 由 PHP 承载，BDS 是 Mojang 的原生可执行文件（`bedrock_server`，`LD_LIBRARY_PATH=<serverDir>`，解压 zip 而不是下载 jar，无 `eula.txt`）。

**BDS 在 arm64 设备上靠 box64 转译运行**（实现：`server/BdsRuntime.kt` + `TermuxRuntime.startBdsViaBox64`）。

官方只发布 Linux x86_64 + glibc 构建，本 APK 只打包 arm64-v8a、运行环境是 bionic，所以启动链是：

1. **glibc 容器**：复用 `ProotContainerProfile`（默认 jammy）的 rootfs。box64 自己是 aarch64 **glibc** 程序，bionic 上跑不起来；容器同时提供 BDS 依赖的 glibc。
2. **box64 + x86_64 运行库**：**随 APK 内置**（`app/src/main/assets/box64/`：
   `box64` 28MB aarch64 可执行文件、`box64.box64rc`、`x86libs/` 7 个库），
   初始化时复制到 `<prefix>/var/lib/mineserve/bds/`，无网络、无解压。

   **`x86libs` 不是可选项**：官方 `bedrock_server` 是**动态链接**的 x86_64 程序，
   `DT_NEEDED` 实测为 `libpthread.so.0 / libdl.so.2 / libm.so.6 / libgcc_s.so.1 /
   libc.so.6 / ld-linux-x86-64.so.2 / librt.so.1`（取自 Ubuntu 22.04 amd64 的
   libc6 + libgcc-s1）。box64 只把 x86_64 指令翻译成 arm64，**库要自己给**；
   缺任何一个都是 `Error loading needed libs in elf .../bedrock_server` + `exit=255`。

   这些库**不能**放进给 box64 进程自己的 `LD_LIBRARY_PATH`：box64 是 aarch64 glibc 程序，
   它的 `libc.so.6` 会被 x86_64 那份顶掉，直接 `wrong ELF class` 起不来。
   启动命令用的是 `BOX64_LD_LIBRARY_PATH`（只影响被翻译程序找库的顺序）：
   `<guestRoot>/x86libs : /srv/mineserve : <guestRoot>/run/<dir>`。
   网络下载路径（`rfort.net` / jsDelivr 的 apt 索引 → `.deb` → 宿主侧解包）保留为**兜底**，只在资源缺失时走到。

   **为什么必须内置**：Debian 的 `.deb` 是 `data.tar.zst`，而安卓上唯一可用的纯 Java zstd 解压库
   （aircompressor）依赖 `sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET` —— **Android 14+ 的 ART 删掉了这个字段**，
   构造 `ZstdInputStream` 就抛 `NoSuchFieldError`。它是 `Error` 而非 `Exception`，
   只 `catch (Exception)` 会一路冒到线程顶层**直接杀掉进程**（用户看到闪退）。
   同一个坑也影响「导入 `.tar.zst` 服务端」：`ServerImporter` 现在会把它转成一条可读的 `IOException`。
3. **可执行副本**：公共存储是 FUSE 且 `noexec`，1.21+ 的 `bedrock_server` 是 **244MB 静态单文件**，既不能就地执行也不能被 mmap，必须复制到 `<prefix>/var/lib/mineserve/bds/payload/<版本>/`。副本按版本共享，判据是**大小一致**（不比对 mtime：FUSE 上 mtime 不稳会导致每次重搬 244MB）。
4. **运行目录**：`.../bds/run/<dirName>/` 里用符号链接指向共享副本，再为 `definitions` / `resource_packs` / `behavior_packs` / `config` / `data` 铺一条指回 `/srv/mineserve/*` 的链接 —— 有些版本按工作目录找、有些按可执行文件所在目录找，两条都铺上才不会「换版本就报找不到原版资源」。
5. **启动**：`proot --rootfs=<rootfs> --bind=<bdsRoot>:/opt/minexa-bds --bind=<serverDir>:/srv/mineserve`，命令是 `cd /srv/mineserve && exec /opt/minexa-bds/box64 /opt/minexa-bds/run/<dir>/bedrock_server`。工作目录仍是服务器目录，所以 `server.properties`、`worlds/`、`logs/` 都落在用户看得到的地方，备份 / 文件管理 / 端口同步一行都不用改。

`BdsSupport.checkLaunch()` 按 ELF 头的 `e_machine` 给出三种结论：本机架构 → `Native` 直接 exec；x86_64 + arm64 设备 → `Box64`（需要 box64 环境）；其它（arm32 / x86 32 位）→ 拒绝并建议改用 PowerNukkitX / Allay。主程序与同目录 `.so` 必须同架构。判定读实际文件，所以将来出现 arm64 官方构建时会自动走原生路径，无需改代码。

**代价必须如实告知用户**（`dl_bds_platform_warning` 四种语言都写了）：首次初始化只需下载 PRoot 容器约 32MB（box64 已内置），占用约 380MB 磁盘，转译启动慢、性能约为原生一半。

内置 box64 让 APK 从 29MB 涨到 35MB（资源在 APK 内被 deflate，28MB 的二进制约占 6MB）。这笔账是划算的：
它换掉的是「三方小主机可用性 + apt 索引解析 + 新系统上根本走不通的 zstd 解包」三重依赖。

### BDS 运行环境是**独立的**一等公民（`server/BdsEnvironmentManager.kt`）

它不是 `JavaVendor` 的一个取值，也不进 `installedJava` 列表：BDS 环境没有 JDK，
跟「选哪个 Java」毫无关系。界面在「依赖与环境管理」里单独占一栏（`ENV_TAB_BDS`），
概览页选中 BDS 核心时「当前运行环境」显示 `BDS 专属 · box64 转译`，
而不是某个 Java 版本 —— 后者会让人以为换个 JDK 就能跑基岩版。

| 关注点 | 规则 |
|---|---|
| 何时初始化 | **只能**由用户在这一栏点「初始化环境」。点启动不会临时装：首次要下 40MB、耗时几分钟，塞进启动路径只会让用户以为卡死，失败也没有地方显示原因 |
| 就绪判据 | `<prefix>/var/lib/mineserve/bds/` 下同时有 `box64`、`runtime.ready` 与 `x86libs/` 里 7 个库（标记里写发行版 + box64 版本）。`BdsEnvironmentManager.isReady()` 是启动链路的硬闸门；缺 x86 库的老安装由 `refresh()` 在后台补齐一次 |
| 启动时 | 未就绪 → 直接抛「请先初始化」（不做静默回退）；容器被单独删掉 → `startBdsViaBox64` 里的 `containerRootfsReady` 检查给出可照做的提示 |
| 状态 | `BdsEnvStatus{installed, installing, stage, percent, error, detail, rawDetail, attempts}`，阶段 `Container(0–60) / Box64(60–92) / Verify(92–100)` 首尾相接，进度不是假滚动条 |
| 失败 | 分类到 `BdsEnvError{Space, Container, Download, Corrupt, Unpack, Verify, Unknown}`，卡片同时展示分类文案、原始异常、逐个下载源的尝试结果；可原地重试（中间产物都在临时目录并在失败/取消时清掉） |
| 移除 | 只删 box64 与就绪标记，**保留容器** —— 容器是发行版级共享资源，Java 版（PRoot 来源）也在用 |
| 跨重建 | 管理器挂在 `McApplication` 上，ViewModel 重建（切页/旋转）不会打断进行中的初始化 |
| 可见性 | 阶段切换、每 10% 进度、**每一次换源**都 `emitLog("[bds] …")` 到控制台。之前只有 proot 自己会写控制台，进入 box64 阶段后彻底安静 —— 用户看到的就是「卡在 `[proot] 正在部署` 那行不动」 |

### BDS 核心的在线更新（`data/BdsBuildCatalog.kt` + `server/BdsUpdater.kt`）

版本来自 Mojang 官方 `net-secondary.web.minecraft-services.net/api/v1.0/download/links`：
同一份响应里 `serverBedrockLinux` 是稳定版、`serverBedrockPreviewLinux` 是 Preview，
版本号只能从 URL 文件名（`bedrock-server-1.26.61.1.zip`）解析，官方不提供 JSON 版本字段。
目录有 10 分钟缓存（`SharedPreferences`），请求失败时回退上次结果并标 `fromCache`。

更新这条链路的核心不是「下载」，而是**不毁用户数据**。官方 1.26.61.1 安装包（93MB / 1392 条目）
里带着 `server.properties`（16KB 默认模板）、`allowlist.json` 与 `permissions.json`（都是 3 字节的 `[]`），
直接解压到服务器目录 = 清空端口、白名单、权限。因此流程固定为：

| 步骤 | 规则 |
|---|---|
| 前置 | 服务器必须已停止（运行中替换核心会让进程继续用旧 inode，界面却显示已更新）、目录必须是 BDS 布局 |
| 备份 | `BackupManager.backupBdsBeforeUpdate` 只备**用户数据面**（`worlds/`、三份配置、`behavior_packs/`、`resource_packs/`、`config/`），不含 265MB 核心；失败即中止，服务器目录一个字节不动。外部存储无权限时退化到私有目录 `home/bds-backup/` |
| 下载 | 走 `MultiSourceDownload`（`.part` + 包级校验：zip 可读、含 `bedrock_server`、体积 ≥ 20MB） |
| 暂存校验 | 解压到临时目录（不在服务器目录内），校验主程序 ELF 架构可运行、体积 ≥ 50MB、`server.properties` 存在 |
| 保留旧核心 | `bedrock_server` 改名为 `bedrock_server.old-<旧版本>`；历史 `.old-*` 只留最近一份（每个 265MB） |
| 合并安装 | 见 `server/BdsInstallRules.kt`：`server.properties` / `allowlist.json` / `permissions.json` 已存在则跳过，`worlds/` 整棵子树不碰，**只写不删**（用户自装的行为包不会因为新包里没有就被删） |
| 落地复检 | 复用 `BdsSupport.checkLaunch` 再判一次「能不能跑」；不通过就删本次新写文件、把 `.old` 改回原名、还原 `bds-version.txt` |
| 收尾 | 写 `bds-version.txt`、更新 `installedCores.version`、清掉旧版本的 265MB 运行副本（`BdsRuntime.payloadDir`）与运行目录里的硬链接 |

界面入口在「服务器管理 → BDS 核心版本」（仅 BDS 核心显示）：当前 / 最新稳定 / Preview 三行 + 更新按钮，
更新前弹确认框逐条列出「会备份什么、会保留什么」。

### 多源下载（`data/DownloadSources.kt`）

原项目有五套各自的「多源」写法，且都不校验内容。现在统一成 `MultiSourceDownload`：

- **来源是数据，不是代码分支**：`BdsSources.box64Deb`（rfort.net HTTPS / rfort.net HTTP / GitHub Pages）
  与 `UbuntuImageSources.archiveSources()`（清华、中科大、北外、南大、上交、阿里云）都是列表，
  加镜像不用改下载逻辑；
- **失败自动换源**：连不上、超时、非 2xx、内容校验不过（错误页/被截断）都算该源失败，
  换下一个；全部失败才抛 `AllSourcesFailed`，异常里带每个源的原因；
- **校验先于使用**：rootfs 按同目录 `SHA256SUMS` 比对整个文件（取不到就退回「大小 + gzip 魔数」，
  不让次要请求判死安装）；box64 的 `.deb` 按 `Packages` 里的 `SHA256:` 比对，
  再验 ar 魔数、解包后的 `e_machine` 必须 aarch64；
- **box64 的源必须是不同主机**，不能只是同一域名换协议：`rfort.net`(https/http) 之外还要有
  jsDelivr 三个边缘（`cdn` / `fastly` / `gcore`，指向 `ryanfortner/box64-debs` 同一仓库）。
  仓库里 `.deb` 与 `Packages` 同目录，所以 `jsDelivr = https://cdn.jsdelivr.net/gh/ryanfortner/box64-debs@master/debian/`；
- **挑包必须按 `Package` 过滤**：索引里除通用 `box64` 外还有 `box64-sd888` / `box64-rk3588` /
  `box64-tegrax1` / `box64-rpi5arm64` 等按 SoC 调的变体，**共用同一行 Version**。
  只比版本号会挑到列表里最后一个（给英伟达 Tegra 编的），装到普通手机上要么慢要么崩。
- **每个源都有硬超时**（`TEXT_TIMEOUT_MS` 15s / `BINARY_TIMEOUT_MS` 180s）+ **总预算** `OVERALL_TIMEOUT_MS` 10min。
  只靠 `HttpURLConnection` 的 connect/read timeout 不够：那是「每次系统调用」的时限，
  一个慢滴的流、或分片线程逐个卡 60s，累加起来就是十几分钟的静默。超时通过
  `DownloadSession.cancel()` 主动断连接实现 —— 否则阻塞中的 socket 读不会被协程取消唤醒；
- **半截文件不会落在目标路径**：先写 `*.part<源序号>`（每源独立，避免超时任务与下一个源同时写同一文件），
  校验通过才 rename；取消/异常也会清掉；
- **明文 http 也能用**：单源下载交给 `MultiThreadDownloader`，它已处理裸 socket 通道
  （`http://rfort.net` 这类源在 Android 上走 `HttpURLConnection` 会被明文策略拒绝）。

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
