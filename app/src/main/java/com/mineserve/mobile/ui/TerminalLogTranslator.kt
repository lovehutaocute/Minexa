package com.mineserve.mobile.ui

/**
 * 终端显示层日志汉化（仅改变显示，不修改原始日志数据）。
 *
 * 规则分三档、按优先级执行：
 *  1. 整行/摘要翻译（fullLineRules）：命中后整行替换为中文摘要，并附原文
 *  2. 正则/短语替换（regexRules、phraseRules）：在原文行内替换高频英文短语，保留玩家名等上下文
 *  3. 前缀标注：ERROR / FATAL / WARN 标记行，加「错误/警告」前缀
 */
object TerminalLogTranslator {

    fun translate(line: String): String {
        // ── 整行 / 摘要级翻译 ──────────────────────────────────────
        fullLineRules.forEach { (pattern, chinese) ->
            if (line.contains(pattern, ignoreCase = true)) {
                return "$chinese\n  原文: $line"
            }
        }
        // ── 正则替换（保留上下文，如玩家名） ────────────────────────
        regexRules.forEach { (rx, replacement) ->
            if (rx.containsMatchIn(line)) {
                return rx.replace(line, replacement)
            }
        }
        // ── 短语替换 ──────────────────────────────────────────────
        phraseRules.forEach { (from, to) ->
            if (line.contains(from, ignoreCase = true)) {
                return line.replace(from, to, ignoreCase = true)
            }
        }
        // ── 前缀标注 ──────────────────────────────────────────────
        return when {
            line.contains("[ERROR]", true) || line.contains("[FATAL]", true) ->
                "错误：$line\n  原文: $line"
            line.contains("[WARN]", true) ->
                "警告：$line\n  原文: $line"
            else -> line
        }
    }

    /** 整行/摘要翻译：contains 命中即整行替换（顺序即优先级） */
    private val fullLineRules = listOf(
        "Done (" to "服务端已启动完成",
        "Starting minecraft server" to "正在启动 Minecraft 服务端",
        "Preparing level" to "正在加载世界",
        "Preparing spawn area" to "正在准备出生点区域",
        "Preparing start region" to "正在准备出生区域",
        "Building terrain" to "正在生成地形",
        "Stopping the server" to "正在停止服务端",
        "Stopping server" to "正在停止服务端",
        "Saving the game" to "正在保存世界",
        "Saved the game" to "世界保存完成",
        "Saving worlds" to "正在保存世界",
        "Saving chunks" to "正在保存区块",
        "Saving players" to "正在保存玩家数据",
        "Flushing chunk data" to "正在写入区块数据",
        "You need to agree to the EULA" to "未接受 Minecraft EULA，服务端无法启动",
        "Can't keep up" to "服务器过载或卡顿（TPS 过低），请检查性能",
        "Address already in use" to "端口已被占用，服务端无法监听",
        "BindException" to "端口绑定失败（可能被占用）",
        "OutOfMemoryError" to "Java 内存不足（OutOfMemoryError）",
        "Java heap space" to "Java 堆内存不足",
        "UnsupportedClassVersionError" to "Java 版本不兼容",
        "class file version" to "class 文件版本不兼容（Java 版本过低）",
        "mod loading has failed" to "模组加载失败或版本不匹配",
        "Mixin apply failed" to "Mixin 注入失败（模组冲突或版本不匹配）",
        "Could not load plugin" to "插件加载失败",
        "Unsupported API version" to "插件 API 版本不匹配，无法加载",
        "You are not white-listed" to "您不在服务器白名单中，无法进入",
        "Authentication servers are down" to "Mojang 验证服务器不可用",
        "Failed to verify username" to "用户名验证失败",
        "Internal Exception" to "内部异常（网络连接中断）",
        "Connection refused" to "连接被拒绝",
        "Connection reset" to "连接被重置",
        "Timed out" to "连接超时",
        "Network is unreachable" to "网络不可达",
        "No route to host" to "无法连接到目标主机",
        "Unknown host" to "未知主机",
        "Broken pipe" to "连接已中断（Broken pipe）",
        "Permission denied" to "权限被拒绝（Permission denied）",
        "No space left on device" to "磁盘空间不足",
        "Read-only file system" to "文件系统为只读",
        "File not found" to "文件或目录不存在",
        "No such file" to "文件或目录不存在",
        "Not a directory" to "路径不是目录",
        "StackOverflowError" to "发生栈溢出（StackOverflowError）",
        "NoClassDefFoundError" to "缺少依赖类（NoClassDefFoundError）",
        "NoSuchMethodError" to "依赖方法缺失或版本不匹配",
        "Exception in thread" to "线程发生异常",
        "Caused by" to "由以下原因引起",
        "The server crashed" to "服务端已崩溃",
        "Minecraft has crashed" to "Minecraft 已崩溃",
        "Saving crash report" to "正在保存崩溃报告",
        "Corruption detected" to "检测到存档损坏",
        "Level is corrupt" to "世界存档已损坏，无法加载",
        "Unable to load world" to "无法加载世界",
        "Failed to load world" to "无法加载世界",
        "Loading plugins" to "正在加载插件",
        "Enabling plugin" to "正在启用插件",
        "Disabling plugin" to "正在禁用插件",
        "Failed to start the minecraft server" to "Minecraft 服务端启动失败",
        "Failed to start" to "启动失败",
        "Shutting down" to "正在关闭",
        "Closing threads" to "正在关闭线程",
        "Closing server" to "正在关闭服务端",
        "Deleting level" to "正在删除世界",
        "Whitelist has been enabled" to "白名单已启用",
        "Whitelist has been disabled" to "白名单已关闭",
        "The server is full" to "服务器已满，无法加入",
        "RCON not running" to "RCON 未开启",
        "Server started" to "服务端已启动",
        "Server is running in offline mode" to "服务器以离线模式运行",
        "RCON running on" to "RCON 远程控制已开启",
        "Starting GS4 status listener" to "已启动 GS4 状态监听",
        "Started query engine" to "已启动查询引擎",
        "Starting server" to "正在启动服务端",
        "Loading properties" to "正在加载服务端配置",
        "Loading server properties" to "正在加载服务端配置",
        // contains 按列表顺序取首个命中：否定的更长规则必须排在对应 Loading 规则之前，
        // 否则 "Unloading world" 会先命中其子串 "loading world"，把卸载/关服误显示为「正在加载世界」
        "Unloading world" to "正在卸载世界",
        "Loading world" to "正在加载世界",
        "Unloading level" to "正在卸载世界",
        "Loading level" to "正在加载世界",
        "Preparing spawn" to "正在准备出生点",
        "Preparing start region" to "正在准备出生区域",
        "Listening on" to "正在监听地址",
        "Done loading" to "加载完成",
        // 同理 "Loaded plugin" 必须排在通用 "Loaded" 之前，否则会被降级显示为「已加载」
        "Loaded plugin" to "插件加载完成",
        "Loaded" to "已加载",
        "Saving level" to "正在保存世界",
        "Reloading" to "正在重新加载",
        "Reload complete" to "重新加载完成",
        "Loading plugins" to "正在加载插件",
        "Enabling plugin" to "正在启用插件",
        "Disabling plugin" to "正在停用插件",
        "Plugin already initialized" to "插件已经初始化",
        "Failed to load" to "加载失败",
        "Failed to enable" to "启用失败",
        "Could not connect" to "无法连接",
        "Connection lost" to "连接已丢失",
        "Login successful" to "登录成功",
        "Invalid username" to "用户名无效",
        "You have been kicked" to "您已被踢出服务器",
        "You have been banned" to "您已被服务器封禁",
        "Server is full" to "服务器已满",
        "Unknown command" to "未知命令",
        "Incorrect argument" to "命令参数错误",
        "Usage:" to "用法：",
        "Permission denied" to "权限不足",
        "No permission" to "权限不足"
    )

    /** 正则替换：保留玩家名等上下文 */
    private val regexRules = listOf(
        Regex("(\\w+) joined the game") to "玩家 $1 加入了游戏",
        Regex("(\\w+) left the game") to "玩家 $1 离开了游戏",
        Regex("There are (\\d+) of a max of (\\d+) players online") to "当前在线 $1/$2 人"
    )

    /** 短语替换：行内替换高频英文短语 */
    private val phraseRules = listOf(
        "lost connection" to "失去连接",
        "disconnected" to "已断开连接",
        "was banned" to "被封禁",
        "was unbanned" to "已被解封",
        "was kicked" to "被踢出服务器",
        "whispers to you" to "悄悄对你说",
        "whispered to you" to "悄悄对你说",
        "moved too quickly" to "移动速度过快",
        "Illegal characters in chat" to "聊天内容包含非法字符",
        "is already connected" to "重复连接",
        "Failed to login" to "登录失败",
        "Cannot join server" to "无法加入服务器",
        "Server closed" to "服务端已关闭",
        "Autosaved" to "已自动保存",
        "Auto-saved" to "已自动保存",
        "Unable to save world" to "无法保存世界",
        "Default game type" to "默认游戏模式",
        "Spawn radius" to "出生点半径",
        "Difficulty" to "游戏难度",
        "players online" to "人在线",
        "joined the game" to "加入了游戏",
        "left the game" to "离开了游戏",
        "has joined" to "已加入服务器",
        "has left" to "已离开服务器",
        "logged in" to "已登录",
        "logged out" to "已退出登录",
        "Starting" to "正在启动",
        "Loading" to "正在加载",
        "Loaded" to "已加载",
        "Preparing" to "正在准备",
        "Stopping" to "正在停止",
        "Saving" to "正在保存",
        "Saved" to "已保存",
        "Enabled" to "已启用",
        "Disabled" to "已禁用",
        "successfully" to "成功",
        "failed" to "失败",
        "online" to "在线",
        "offline" to "离线"
    )
}
