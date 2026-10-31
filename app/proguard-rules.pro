# ===== Minexa R8 规则 =====
# 包名已由 com.mcserver.manager 迁移为 com.mineserve.mobile

# 保留 Termux bridge 接口（反射调用 native helper）
-keep class com.mineserve.mobile.runtime.** { *; }
-keep class com.mineserve.mobile.service.** { *; }
# 服务器管理与安装链路（PluginManager/下载/隧道等，防混淆运行时异常）
-keep class com.mineserve.mobile.server.** { *; }

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

# @Serializable 类：保留 Companion 与生成的 serializer 描述符
-keep,includedescriptorclasses class com.mineserve.mobile.**$$serializer { *; }
-keepclassmembers class com.mineserve.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.mineserve.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.serialization 内部
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 枚举（序列化/配置使用，保留 name 与序数语义）
-keepclassmembers enum * { *; }

# ---------- Compose ----------
# 【勿恢复】这里原本是 `-keep class androidx.compose.** { *; }`。
# 它让 R8 的裁剪对整棵 Compose 依赖树失效：seeds.txt 里因此多出 3.4 万个
# material-icons-extended 图标成员（每个图标 = 一个 ImageVector getter + 缓存字段），
# 未压缩 dex 被撑到 22.5 MB。项目实际只用到 81 个图标。
# Compose 各 AAR 自带 consumer-rules.pro（见 mapping/configuration.txt 里
# "LocalLifecycleOwner"、"ViewLayerContainer" 那批），业务侧不需要任何额外 keep。
# 若日后出现 Compose 相关的 NoSuchMethodError / 反射失败，请针对具体类补最小规则，
# 绝不要恢复包级 `{ *; }`。

# Keep native entry points, dispatcher discovery, and manifest components.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keep class kotlinx.coroutines.android.** { *; }
-keep public class com.mineserve.mobile.McApplication { *; }
-keep public class com.mineserve.mobile.MainActivity { *; }
-keep public class com.mineserve.mobile.service.** { *; }
-keep public class com.mineserve.mobile.BootReceiver { *; }

# ---------- 数据/工具 ----------
# xz / commons-compress / aircompressor 无反射，无需规则

# ---------- FTP 服务（Apache FTPServer / Mina） ----------
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.mina.**
-dontwarn org.slf4j.**
# ftpserver-core 的可选 Spring 集成类在 Android 上无 Spring 依赖，忽略缺失告警
-dontwarn org.springframework.**
