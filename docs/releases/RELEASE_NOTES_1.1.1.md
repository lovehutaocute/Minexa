# MineServe Mobile 1.1.1

> Historical release notes for 1.1.1. For current capabilities and requirements, see [README](./README.md) and [CHANGELOG](./CHANGELOG.md).

## 更新内容

- 修复应用自动检查更新后不弹窗、不发送通知的问题。
- 修复更新 APK 下载失败时只能使用 GitHub 单一地址的问题，增加备用镜像回退。
- 增加 APK 文件头和大小校验，避免把错误页面或不完整文件当作安装包。
- 修复下载失败后临时 `.part` 文件残留问题。
- 修复下载完成后无法调用系统安装器的问题。
- Android 8.0 及以上首次安装更新时，会引导用户开启“允许安装未知应用”。
- 保留“本次不更新”选项，并继续显示服务器核心和世界数据可能丢失的红色提醒。
- 保留更新说明展示、下载进度、下载完成后二次确认和失败重试流程。
- Java 8 Ubuntu 安装前自动恢复中断的 `dpkg` 事务，避免因旧安装状态导致安装失败。
- Java 8 仍只运行在 Ubuntu ARM64 glibc 环境，不修改 Java 17、21、25。
- Release 构建继续启用 R8 代码混淆和资源压缩。

## 重要提醒

更新应用前请先备份服务器核心、世界、插件和配置。应用更新过程可能因系统安装、存储权限或用户操作导致现有数据不可用。
