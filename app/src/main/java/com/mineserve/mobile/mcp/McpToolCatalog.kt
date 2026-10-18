package com.mineserve.mobile.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 工具目录（纯 JVM 数据，可单测）：
 * 覆盖服务器管理、MC 控制台、Termux 命令、服务器文件管理与模组安装。
 */
object McpToolCatalog {

    /** 全部内置工具定义（顺序即 tools/list 展示顺序） */
    fun definitions(): List<McpToolDef> = listOf(
        // ── 服务器管理 ──────────────────────────────────────────
        McpToolDef(
            name = "get_server_status",
            description = "获取 Minecraft 服务器运行状态：是否运行、启动阶段、在线玩家、TPS、内存、CPU、运行时长与当前活动服务器。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "list_servers",
            description = "列出应用中所有已安装的 Minecraft 服务器，包括核心类型、版本以及哪一个是当前活动服务器。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "select_server",
            description = "切换当前活动服务器（会持久化保存）。文件、模组、启动等工具默认作用于活动服务器。",
            inputSchema = schema(
                "server" to strProp("服务器名称或目录名，见 list_servers 返回结果"),
                required = listOf("server")
            )
        ),
        McpToolDef(
            name = "start_server",
            description = "启动一个 Minecraft 服务器。可先切换到指定服务器再启动。立即返回；用 get_server_status 轮询启动进度。",
            inputSchema = schema(
                "server" to strProp("可选：要切换并启动的服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "stop_server",
            description = "优雅停止正在运行的 Minecraft 服务器（向控制台发送 stop 命令）。",
            inputSchema = schema()
        ),
        // ── MC 控制台 ──────────────────────────────────────────
        McpToolDef(
            name = "send_command",
            description = "向正在运行的服务器控制台发送命令，例如 list、say hello、op Steve。不要带开头的斜杠。",
            inputSchema = schema(
                "command" to strProp("控制台命令，不带开头的 /"),
                required = listOf("command")
            )
        ),
        McpToolDef(
            name = "get_console_logs",
            description = "读取某服务器控制台日志（logs/latest.log）的末尾若干行，重启后仍可读取历史输出。",
            inputSchema = schema(
                "lines" to intProp("返回末尾的行数，1-2000（默认 100）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        // ── Termux 运行环境 ─────────────────────────────────────
        McpToolDef(
            name = "run_termux_command",
            description = "在应用的 Termux 运行环境里执行一条 shell 命令（与应用内 Termux 终端会话同一环境），返回合并输出与退出码。可用于诊断、运行时管理以及服务器目录之外的文件操作。",
            inputSchema = schema(
                "command" to strProp("shell 命令，例如 'java -version'、'ls ~/servers'、'df -h'"),
                "timeout_sec" to intProp("超时秒数，5-300（默认 60）；超时后返回已捕获的部分输出"),
                required = listOf("command")
            )
        ),
        // ── 服务器文件管理（限定在服务器目录内） ──────────────────
        McpToolDef(
            name = "list_files",
            description = "列出服务器目录内的一个目录（世界、插件、模组、配置等）。路径相对于服务器根目录。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的目录路径（默认：服务器根目录）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "read_file",
            description = "读取服务器目录内的文本文件（配置、properties、日志等），二进制文件会被拒绝。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "max_bytes" to intProp("最多返回的字节数，上限 524288（默认 131072）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "write_file",
            description = "在服务器目录内创建或覆盖一个文本文件（如 server.properties、配置 yml）。上限 1MB，父目录会自动创建。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "content" to strProp("要写入的完整文本内容（UTF-8）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "content")
            )
        ),
        McpToolDef(
            name = "delete_file",
            description = "删除服务器目录内的文件或目录（递归删除）。服务器根目录本身不允许删除。" +
                "必须显式传 confirm=true，否则只返回提示、不做任何事。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的路径"),
                "confirm" to boolProp("必须为 true 才真正执行删除"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "confirm")
            )
        ),
        McpToolDef(
            name = "rename_file",
            description = "在服务器目录内重命名或移动文件/目录，目标必须不存在。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的源路径"),
                "new_path" to strProp("相对服务器根目录的目标路径"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "new_path")
            )
        ),
        McpToolDef(
            name = "make_dir",
            description = "在服务器目录内创建目录（自动创建父目录）。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的目录路径"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "upload_file",
            description = "把电脑上的二进制文件（模组 jar、世界 zip、地图压缩包等）上传到服务器目录。content_base64 为标准 base64（也接受 data: URI 前缀）。大于约 12MB 的文件需分块发送：首块 append=false，后续块 append=true。zip 压缩包可用 extract_archive 解压。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "content_base64" to strProp("base64 编码的文件内容（单块不超过约 16MB base64 文本）"),
                "append" to strProp("设为 true 时追加写入已有文件而不是覆盖"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "content_base64")
            )
        ),
        McpToolDef(
            name = "extract_archive",
            description = "解压服务器目录内的 zip/tar/tar.gz/tar.bz2/tar.xz 压缩包（例如通过 upload_file 上传的世界存档或模组目录）。不安全的条目（路径穿越、符号链接）会自动跳过。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的压缩包路径"),
                "dest" to strProp("解压目标目录，相对服务器根目录（默认：服务器根目录）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "import_server",
            description = "从 upload_file 上传的压缩包导入一个新服务器：解压到应用的服务器目录并注册到服务器列表，自动识别核心类型与版本（识别不出也能登记）。压缩包路径相对于当前活动服务器目录（即 upload_file 的落点），导入完成后会删除压缩包。",
            inputSchema = schema(
                "archive" to strProp("压缩包路径，相对当前活动服务器根目录，例如 'mcp-import-staging.zip'"),
                "name" to strProp("新服务器的显示名称"),
                required = listOf("archive", "name")
            )
        ),
        // ── 模组（Modrinth） ────────────────────────────────────
        McpToolDef(
            name = "list_mods",
            description = "列出某服务器已安装的模组（文件名、大小、启用状态）。",
            inputSchema = schema(
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "search_mods",
            description = "在 Modrinth 搜索模组。默认按活动服务器的 MC 版本与加载器过滤，最多返回 20 条。",
            inputSchema = schema(
                "query" to strProp("搜索关键词，例如 'sodium'"),
                "mc_version" to strProp("可选：MC 版本过滤，例如 '1.21.1'"),
                "loader" to strProp("可选：加载器过滤：fabric / forge / neoforge / quilt"),
                required = listOf("query")
            )
        ),
        McpToolDef(
            name = "install_mod",
            description = "从 Modrinth 下载并安装模组到服务器的 mods 目录（重启服务器后生效）。",
            inputSchema = schema(
                "slug" to strProp("Modrinth 项目的 slug 或 ID（来自 search_mods）"),
                "mc_version" to strProp("可选：MC 版本；默认为目标服务器的版本"),
                "loader" to strProp("可选：加载器；默认按目标服务器的核心类型推断"),
                "server" to strProp("可选：目标服务器；缺省为当前活动服务器"),
                required = listOf("slug")
            )
        ),

        // ── 运行环境与依赖 ──────────────────────────────────────
        McpToolDef(
            name = "get_runtime_info",
            description = "运行环境总览：Termux 是否就绪、每个 Java 运行时（两套来源 × 各自版本，键形如 Termux.Java17 / " +
                "Fcl.Java21）的安装与可用性诊断、已装 PHP、依赖步骤（JDK/Wget/Frp/Rclone/PRoot）状态、" +
                "关键命令是否在 PATH、下载源设置、运行目录剩余空间。" +
                "开始任何测试前先调用它判断当前环境处于什么状态。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "install_java",
            description = "安装指定的 Java 运行时。有两套来源：vendor=Termux（apt 装的 openjdk，8/11/17/21/25，" +
                "需要 Termux 环境已就绪）与 vendor=Fcl（FoldCraftLauncher 血统的 Android 原生 JRE，17/21/25，" +
                "App 内在线下载、与 Termux 环境无关、自带 IPv6 支持）。缺省 vendor=Termux。" +
                "首次安装需下载数十 MB，耗时几分钟——立即返回 job id，用 get_job 轮询，或传 wait=true 在本调用内等待完成。" +
                "装好 Java 8（Termux）或任意 FCL 版本会自动把它设为所选运行时。",
            inputSchema = longTaskSchema(
                "version" to strProp("Java 版本：8 / 11 / 17 / 21 / 25（也接受 Java17 这种写法；FCL 只有 17/21/25）"),
                "vendor" to strProp("可选：Termux 或 Fcl，缺省 Termux"),
                required = listOf("version")
            )
        ),
        McpToolDef(
            name = "remove_java",
            description = "卸载指定来源、指定版本的 Java 运行时（直接删除文件，另一个来源不受影响）。" +
                "服务器运行中会被拒绝。必须显式传 confirm=true，否则只返回提示。",
            inputSchema = schema(
                "version" to strProp("Java 版本：8 / 11 / 17 / 21 / 25"),
                "vendor" to strProp("可选：Termux 或 Fcl，缺省 Termux"),
                "confirm" to boolProp("必须为 true 才真正执行卸载"),
                required = listOf("version", "confirm")
            )
        ),
        McpToolDef(
            name = "reinstall_java",
            description = "重装修复 Java 运行时。vendor=Termux（缺省）走「清空并重装」，修复 bin/java wrapper " +
                "指向错误路径、JVM 安装目录缺失等「日志说装好了但 java -version 报 not found」的场景；" +
                "vendor=Fcl 则删掉该版本重新下载。耗时较长，走后台任务。",
            inputSchema = longTaskSchema(
                "version" to strProp("可选：Java 版本；缺省用当前所选的版本"),
                "vendor" to strProp("可选：Termux 或 Fcl，缺省用当前所选的来源")
            )
        ),
        McpToolDef(
            name = "install_php",
            description = "安装 PHP 运行时（PocketMine-MP 服务端用）。下载几十 MB，走后台任务，用 get_job 轮询。",
            inputSchema = longTaskSchema(
                "version" to strProp("可选：PHP 版本，当前只有 Php82")
            )
        ),
        McpToolDef(
            name = "remove_php",
            description = "卸载 PHP 运行时。服务器运行中会被拒绝。",
            inputSchema = schema(
                "version" to strProp("可选：PHP 版本，当前只有 Php82")
            )
        ),
        McpToolDef(
            name = "install_dependencies",
            description = "一键安装运行依赖（wget、字体运行库、frp、rclone、proot），走 apt。" +
                "耗时几分钟，走后台任务，用 get_job 轮询。服务器运行中会被拒绝：" +
                "apt 与 MC 服务端共用同一个 rootfs，实测运行中安装会把 MC 进程 SIGKILL 掉（exit=137）。",
            inputSchema = longTaskSchema()
        ),
        McpToolDef(
            name = "remove_dependencies",
            description = "清除已安装的 Termux 依赖（wget/frp/rclone/proot 等），用于验证环境重装流程。服务器运行中会被拒绝。" +
                "必须显式传 confirm=true，否则只返回提示。",
            inputSchema = schema(
                "confirm" to boolProp("必须为 true 才真正执行清除"),
                required = listOf("confirm")
            )
        ),
        McpToolDef(
            name = "install_apt_package",
            description = "通过 apt 安装任意 Termux 包（如 'curl'、'unzip'、'openssh'）。包名会做字符白名单校验，" +
                "非法字符直接拒绝。走后台任务，用 get_job 轮询。",
            inputSchema = longTaskSchema(
                "packages" to strProp("要安装的包名，数组或逗号分隔字符串，例如 [\"curl\",\"unzip\"]"),
                required = listOf("packages")
            )
        ),
        McpToolDef(
            name = "repair_runtime",
            description = "修复 Termux 运行环境。target 取值：" +
                "auto（自动修复，含字体）、refresh（刷新环境变量与符号链接）、rootfs、permissions、" +
                "commands（重建已装命令的链接）、scripts、dpkg、aptconfig、usrbin、apt（软件源+apt 自愈）、" +
                "jvm（补齐所选 JVM）、fonts。走后台任务。",
            inputSchema = longTaskSchema(
                "target" to strProp("修复目标，默认 auto")
            )
        ),
        McpToolDef(
            name = "check_command",
            description = "检查某个命令在 Termux 环境里是否可用；不可用时返回诊断信息（为什么找不到、链接指向哪里）。",
            inputSchema = schema(
                "command" to strProp("命令名，例如 java、wget、frpc"),
                required = listOf("command")
            )
        ),
        McpToolDef(
            name = "set_download_mirror",
            description = "切换运行环境 / 服务端核心的下载源（GitHub 官方或若干加速镜像）。切换后下一次下载生效。",
            inputSchema = schema(
                "mirror" to strProp("镜像枚举名：Auto / Ghfast / GhProxy / CdnGhProxy / GhproxyNet / GitHub"),
                required = listOf("mirror")
            )
        ),
        McpToolDef(
            name = "set_apt_mirror",
            description = "切换 apt 软件源（JDK/wget/frp 等依赖包的下载地址），会同时重写 Termux 的 sources.list。",
            inputSchema = schema(
                "mirror" to strProp("镜像枚举名：Tuna / Aliyun / Ustc / Nju"),
                required = listOf("mirror")
            )
        ),

        // ── 服务端核心与实例 ────────────────────────────────────
        McpToolDef(
            name = "list_core_versions",
            description = "查询某个服务端核心可用的 Minecraft 版本列表（实时向上游 API 拉取）。" +
                "用 create_server 之前先用它确认版本号写法。",
            inputSchema = schema(
                "core" to strProp("核心类型：Paper / Purpur / Leaves / Leaf / Spigot / CraftBukkit / Fabric / Forge / NeoForge / Quilt / Vanilla / Velocity / BungeeCord / PowerNukkitX / PocketMine / Allay"),
                "limit" to intProp("最多返回多少个版本（默认 60，上限 400）"),
                required = listOf("core")
            )
        ),
        McpToolDef(
            name = "create_server",
            description = "下载并安装一个新的服务端实例：自动创建目录、写入 eula.txt、建 plugins/、注册到服务器列表并设为活动服务器。" +
                "下载几十到几百 MB，走后台任务；用 wait_for_server 等待启动、get_job 查看下载进度。" +
                "Forge/NeoForge/Quilt 需要先装好相应 Java。",
            inputSchema = longTaskSchema(
                "core" to strProp("核心类型，见 list_core_versions"),
                "version" to strProp("Minecraft 版本，例如 1.20.4；见 list_core_versions"),
                "name" to strProp("可选：服务器显示名称，缺省为「核心 + 版本」"),
                required = listOf("core", "version")
            )
        ),
        McpToolDef(
            name = "delete_server",
            description = "删除一个服务器实例：连同它的世界存档、配置、插件一并永久删除，并从列表移除。" +
                "必须显式传 confirm=true，否则只返回提示。测试后清理环境用。",
            inputSchema = schema(
                "server" to strProp("服务器名称或目录名；缺省为当前活动服务器"),
                "confirm" to boolProp("必须为 true 才真正执行删除"),
                required = listOf("confirm")
            )
        ),

        // ── 应用配置 ────────────────────────────────────────────
        McpToolDef(
            name = "get_config",
            description = "读取应用配置（端口、堆内存、自动重启、所选运行时、备份策略、下载源、定时开关等）。" +
                "注意：出于安全，MCP 令牌与开关不在此暴露。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "set_config",
            description = "修改一项应用配置并立即持久化，用于布置测试条件（例如改端口、关自动重启、缩短备份间隔）。" +
                "可写键见工具返回的错误提示。mcpEnabled / mcpPort / mcpAllowLan / mcpToken 不可通过 MCP 修改——" +
                "改了会断掉当前连接，只能去 App 界面改。",
            inputSchema = schema(
                "key" to strProp("配置键名，例如 localPort、maxHeapMb、autoRestartOnCrash、selectedJavaVendor、selectedJavaVersion"),
                "value" to strProp("新值（统一用字符串传，布尔用 true/false）"),
                required = listOf("key", "value")
            )
        ),

        // ── 世界快照 ────────────────────────────────────────────
        McpToolDef(
            name = "list_snapshots",
            description = "列出所有世界快照（home/snapshots/*.zip），含大小与创建时间。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "create_snapshot",
            description = "为指定服务器的世界目录创建一份快照（zip 存到 home/snapshots/），" +
                "返回的文件名可直接传给 list_snapshots / restore_snapshot / delete_snapshot。" +
                "会按配置的 maxSnapshots 清理最旧的快照。世界较大时耗时较久，走后台任务。",
            inputSchema = longTaskSchema(
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "restore_snapshot",
            description = "用一份快照覆盖指定服务器的世界目录（运行中会先停服再还原）。走后台任务。" +
                "提示：还原会丢失当前世界进度，测试前建议先 create_snapshot。",
            inputSchema = longTaskSchema(
                "snapshot" to strProp("快照文件名，见 list_snapshots"),
                "confirm" to boolProp("必须为 true 才真正执行还原"),
                "server" to strProp("可选：目标服务器；缺省为当前活动服务器"),
                required = listOf("snapshot", "confirm")
            )
        ),
        McpToolDef(
            name = "delete_snapshot",
            description = "删除一份快照文件。必须显式传 confirm=true，否则只返回提示。",
            inputSchema = schema(
                "snapshot" to strProp("快照文件名，见 list_snapshots"),
                "confirm" to boolProp("必须为 true 才真正执行删除"),
                required = listOf("snapshot", "confirm")
            )
        ),

        // ── 插件 ────────────────────────────────────────────────
        McpToolDef(
            name = "list_plugins",
            description = "列出服务器 plugins/ 目录下的插件（文件名、插件名、版本、启用状态），并附带冲突检测结果。",
            inputSchema = schema(
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "install_plugin",
            description = "从直链 URL 下载插件 jar 并放入 plugins/（覆盖同名文件时失败会自动回滚）。" +
                "服务器运行中会被拒绝。走后台任务。",
            inputSchema = longTaskSchema(
                "url" to strProp("插件 jar 的 http/https 直链"),
                "name" to strProp("可选：保存的文件名（缺省从 URL 推导）"),
                "server" to strProp("可选：目标服务器；缺省为当前活动服务器"),
                required = listOf("url")
            )
        ),
        McpToolDef(
            name = "toggle_plugin",
            description = "启用/停用插件（通过给文件名加/去前缀 '-' 实现）。服务器运行中会被拒绝。",
            inputSchema = schema(
                "file_name" to strProp("插件文件名，见 list_plugins"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("file_name")
            )
        ),
        McpToolDef(
            name = "delete_plugin",
            description = "删除插件文件，可选一并删除它的数据目录 plugins/<插件名>/。服务器运行中会被拒绝。" +
                "必须显式传 confirm=true，否则只返回提示。",
            inputSchema = schema(
                "file_name" to strProp("插件文件名，见 list_plugins"),
                "confirm" to boolProp("必须为 true 才真正执行删除"),
                "also_remove_data" to boolProp("是否同时删除插件数据目录（默认 false）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("file_name", "confirm")
            )
        ),

        // ── 日志与诊断 ──────────────────────────────────────────
        McpToolDef(
            name = "get_app_log",
            description = "读取应用自身的运行日志（Bootstrap、安装、Java/PHP、修复流程的输出都在这里）。" +
                "排查「操作失败但界面只说了一句失败」时最有用。",
            inputSchema = schema(
                "lines" to intProp("返回末尾行数，1-2000（默认 200）")
            )
        ),
        McpToolDef(
            name = "clear_app_log",
            description = "清空应用日志。开始一轮测试前调用，可以让之后的日志只包含本次操作。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "get_last_run_log",
            description = "读取上一次服务端启动的完整日志（服务端进程的原始输出，含堆栈），重启后仍可读。",
            inputSchema = schema(
                "max_bytes" to intProp("最多返回字节数，1024-4194304（默认 262144）")
            )
        ),
        McpToolDef(
            name = "get_device_info",
            description = "设备与进程信息：Android 版本、ABI、CPU 核数、设备内存、应用数据分区剩余空间、堆上限。" +
                "用来判断「内存不足」「存储不足」这类环境问题。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "check_port",
            description = "检查某个 TCP 端口是否可连接（默认 127.0.0.1）。用来确认服务端真的在监听，" +
                "比只看 isRunning 更可靠。",
            inputSchema = schema(
                "port" to intProp("端口号，1-65535"),
                "host" to strProp("可选：主机地址（默认 127.0.0.1；可填局域网 IP）"),
                "timeout_ms" to intProp("连接超时毫秒数，200-10000（默认 1500）"),
                required = listOf("port")
            )
        ),
        McpToolDef(
            name = "http_probe",
            description = "从本机发起一次 HTTP 请求并返回状态码、响应头与前 4KB 响应体。" +
                "用于验证内网穿透的公网地址、MCP/FTP 端点、以及任意需要外部确认的接口。支持 GET/HEAD/POST/PUT/DELETE/OPTIONS。" +
                "明文 http:// 也支持（走裸 socket，不受 Android 明文流量策略限制，所以 127.0.0.1 与局域网地址都能探）。",
            inputSchema = schema(
                "url" to strProp("完整的 http/https URL"),
                "method" to strProp("可选：请求方法，默认 GET"),
                "body" to strProp("可选：请求体（PUT/POST 时以 JSON 发送）"),
                "timeout_ms" to intProp("超时毫秒数，500-60000（默认 10000）"),
                required = listOf("url")
            )
        ),

        // ── 自动化测试辅助 ──────────────────────────────────────
        McpToolDef(
            name = "wait_for_server",
            description = "阻塞轮询直到服务器状态满足条件，然后返回。condition 取值：" +
                "ready（已启动完成）/ running（进程在跑）/ stopped（已停止）/ failed（启动失败）/" +
                "phase:<阶段名>（如 phase:CreatingWorld）。比反复手动 get_server_status 省事得多。" +
                "命中 ready/running 后默认再静默 2000ms 才返回：Paper 打完 'Done' 到控制台" +
                "真正接受指令之间有个很短的窗口，期间的 send_command 会被服务端以 NPE 吞掉。" +
                "紧跟 wait_for_server 发指令的自动化流程不要关掉它（settle_ms=0）。",
            inputSchema = schema(
                "condition" to strProp("等待条件，默认 ready"),
                "timeout_sec" to intProp("超时秒数，1-1800（默认 180）"),
                "poll_ms" to intProp("轮询间隔毫秒，200-10000（默认 1000）"),
                "settle_ms" to intProp("命中 ready/running 后的静默期毫秒，0-15000（默认 2000；0 = 不等）")
            )
        ),
        McpToolDef(
            name = "wait_for_log",
            description = "阻塞轮询服务端日志（logs/latest.log）直到出现匹配正则的行，返回匹配到的行。" +
                "适合判定「启动成功」「玩家加入」「报错」等事件，比按时间盲等可靠。",
            inputSchema = schema(
                "pattern" to strProp("正则表达式，例如 'Done \\\\(.*\\\\)! For help'"),
                "timeout_sec" to intProp("超时秒数，1-1800（默认 120）"),
                "ignore_case" to boolProp("是否忽略大小写（默认 false）"),
                "poll_ms" to intProp("轮询间隔毫秒，200-10000（默认 1000）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("pattern")
            )
        ),
        McpToolDef(
            name = "file_hash",
            description = "计算服务器目录内某个文件的摘要（md5/sha1/sha256），用来校验上传是否完整、文件是否被改动。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "algo" to strProp("摘要算法：md5 / sha1 / sha256（默认 sha256）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),

        // ── 后台任务 ────────────────────────────────────────────
        McpToolDef(
            name = "get_job",
            description = "查询一个后台任务的进度：状态（running/succeeded/failed）、耗时、日志尾部。" +
                "安装 Java/PHP、装依赖、下载核心、还原快照等长任务都会返回 job id，用它轮询。",
            inputSchema = schema(
                "job_id" to strProp("任务 id，形如 job-3"),
                "lines" to intProp("返回日志末尾行数，0-500（默认 200）"),
                required = listOf("job_id")
            )
        ),
        McpToolDef(
            name = "list_jobs",
            description = "列出最近的后台任务（最新的在前），用于确认没有任务还在跑。",
            inputSchema = schema()
        )
    )

    // ── schema 构建辅助 ─────────────────────────────────────────

    private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { (name, prop) -> put(name, prop) }
            })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    /**
     * 长任务的统一入参：在业务参数之外追加 wait / timeout_sec 两个可选字段。
     *
     * 为什么不用「同步执行 + 超时」：安装 Java、拉核心、apt 装依赖动辄几分钟，
     * 在 HTTP 请求里同步等必然先撞上客户端超时，调用方只会看到连接中断、
     * 分不清是任务失败还是还没跑完。所以默认立即返回 job id，
     * 想省一次往返的调用方可以传 wait=true。
     */
    private fun longTaskSchema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList()
    ): JsonObject = schema(
        *properties,
        "wait" to boolProp("是否在本调用内等待任务完成（默认 false：立即返回 job id，用 get_job 轮询）"),
        "timeout_sec" to intProp("wait=true 时的等待上限秒数，5-3600（默认 600）"),
        required = required
    )

    private fun strProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun boolProp(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
}

