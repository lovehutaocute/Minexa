package com.mineserve.mobile.runtime

/**
 * 判断一条 `/proc/<pid>/cmdline` 是否属于指定的那个 MC 服务端目录。
 *
 * ## 为什么这段逻辑值得单独存在并测试
 *
 * `killOrphanMcProcess` 靠 cmdline 里的**路径子串**判断「这个 java 进程
 * 是不是某个服务端的孤儿」。两个方向出错都很难查：
 *
 * - **漏判**：容器里的孤儿 java 清理不掉，继续占着 `world/session.lock`，
 *   用户下次启动看到 `already locked`，而日志里那句「清理残留服务器进程」
 *   根本没出现过 —— 现象与原因隔得很远；
 * - **误判**：把**正常运行**的服务端当孤儿 SIGKILL 掉，表现为
 *   「启动中突然秒退且没有任何报错」，还会被上层当成崩溃并触发自动重启。
 *
 * 早期实现只匹配宿主 `serverDir` 绝对路径，在 PRoot 容器下必然漏判，
 * 因此这里把判断抽成纯函数，让它能被单测穷举覆盖，而不必依赖设备。
 *
 * ## 为什么必须同时接受两种路径
 *
 * PRoot 会改写路径：容器里的 java 通过
 * `--bind=<宿主 serverDir>:/srv/mineserve` 看到的是**来宾路径**，
 * 而 `/proc/<pid>/cmdline` 记录的是进程收到的**原始 argv**。
 * 两种形态都真实存在：
 * - 只认宿主路径 → 容器里的孤儿匹配不到；
 * - 只认来宾路径 → 宿主链路的残留清不掉。
 *
 * ## 已知局限（刻意保留，并在测试里钉住）
 *
 * [TermuxRuntime.GUEST_SERVER_DIR]（`/srv/mineserve`）是**全局固定**挂载点，
 * 因此单凭本函数无法区分「哪个服务端实例」—— 任何容器里的服务端都会匹配。
 * 跨实例的精确归属必须由调用方的进程树排除（wrapper 及其全部后代）
 * 与 `mcServerDir` 共同保证。不要试图靠 cmdline 里的来宾路径区分实例，那做不到。
 *
 * @param cmdline `/proc/<pid>/cmdline` 的内容（NUL 会被调用方替换为空格）
 * @param serverDirAbsolutePath 宿主上该服务端目录的绝对路径
 */
internal fun matchesManagedServer(
    cmdline: String,
    serverDirAbsolutePath: String
): Boolean = cmdline.contains(serverDirAbsolutePath) ||
    cmdline.contains(TermuxRuntime.GUEST_SERVER_DIR)
