# MineServe Mobile 1.2.0

## 服务器导入与识别

- 新增文件夹、压缩包、单个 JAR 与 Modrinth `.mrpack` 的独立导入入口。
- 支持 ZIP、TAR、TAR.GZ/TGZ、TAR.XZ/TXZ、TAR.BZ2/TBZ2、TAR.ZST/TZST、TAR.LZ4 与 7z。
- 自动识别 10 类核心与版本：包括 PowerNukkitX、BungeeCord 及常见 Java 服务端。
- `.mrpack` 自动下载服务端文件、校验 SHA-1 并应用服务端 overrides；CurseForge 整合包暂不支持直接导入。

## 下载、日志与保活

- 下载页采用紧凑核心选项；切换核心会刷新版本列表，阻止旧请求覆盖新结果。
- 崩溃报告汇总全部已安装服务端的原生 `crash-reports/`，保留本地离线分析。
- 增加可选、可拖动的运行状态悬浮条，显示尽力采样的 CPU 与内存。
- 改善 Java 运行时、终端日志和后台保活路径的兼容性。

## 发布信息

- Android 8.0+、`arm64-v8a`。
- Release 包已启用 R8 代码混淆和资源压缩。
- APK：`MineServeMobile-arm64-v8a-release.apk`
- SHA-256：`C344F35B262A6FD97446B057D22EE51FCC3D399087065F1AD93DBD4D791FE8A6`

## 验证

- `:app:testDebugUnitTest`：31 个测试全部通过。
- `:app:assembleRelease`：通过，已生成 R8 mapping。
