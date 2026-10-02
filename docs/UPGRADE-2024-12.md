# 依赖升级说明（2024-12 批次）

> 历史升级记录，不代表当前依赖、SDK 或构建要求。当前使用说明见 [README](../README.zh-CN.md) 与 [CHANGELOG](../CHANGELOG.md)。

本批次在 `libs.versions.toml` 中升级了以下依赖（保守选择：同主版本内的 patch/minor，或已被广泛验证的稳定版）：

| 依赖 | 原版本 | 新版本 | 说明 |
|---|---|---|---|
| AGP (Android Gradle Plugin) | 8.5.2 | 8.7.3 | 需 Gradle 8.9+（当前 wrapper 8.9）、JDK 17 |
| Kotlin | 2.0.20 | 2.0.21 | patch 版本，compose 插件版本随之对齐 |
| Compose BOM | 2024.09.03 | 2024.12.01 | material3 1.3.1 / foundation 1.7.6 |
| androidx.core-ktx | 1.13.1 | 1.15.0 | |
| lifecycle | 2.8.6 | 2.8.7 | |
| activity-compose | 1.9.2 | 1.9.3 | |
| kotlinx-serialization | 1.7.3 | 1.8.0 | 需 Kotlin 2.0.20+ |
| kotlinx-coroutines | 1.9.0 | 1.10.1 | 需 Kotlin 2.0+ |
| work-runtime | 2.9.1 | 2.10.0 | |
| kotlin-test-junit | 2.0.20 | 2.0.21 | 与 Kotlin 对齐 |

## 重要：需要联网构建一次

- 本机 Gradle 缓存中没有上述新版本，**离线构建（--offline）将无法解析依赖**。
- 首次联网执行：`gradlew.bat assembleDebug`（或 `gradlew.bat testDebugUnitTest`）会自动下载新依赖。
- CI（.github/workflows/build.yml）每次构建均为在线环境，不受影响。

## 升级后建议验证项

1. `assembleDebug` 与 `testDebugUnitTest` 通过（单元测试会首次拉取 junit）。
2. 真机回归：主界面、终端、下载、备份/导入、设置（Compose BOM 升级可能带来细微视觉差异）。
3. compileSdk 已升级至 35（已安装 android-35 平台）；**targetSdk 维持 28**——经真机验证，部分 ROM/设备对 targetSdk ≥ 30 的应用禁止执行 app 数据目录中的原生程序（apt-get 等报 Permission denied error=13），28 无此限制。本应用为侧载分发，功能可靠性优先。
4. 若遇到 Kotlin 2.0.21 编译器新警告，多为弃用提示，不影响构建。

## 同时完成

- 全部剩余硬编码中文字符串已迁入 strings.xml（见提交信息），单元测试依赖已就绪。

## 勘误

- 本文档原先还列有 `navigation-compose 2.8.1 → 2.8.5` 一行，但**本项目从未依赖 androidx navigation**：`gradle/libs.versions.toml` 中没有该条目，应用内也没有 `NavHost`/`NavController`。页面切换由手写的标签组件 `app/src/main/java/com/mineserve/mobile/ui/McTab.kt` 实现，因此该行已删除。表中其余各行与当前 `libs.versions.toml` 一致。
