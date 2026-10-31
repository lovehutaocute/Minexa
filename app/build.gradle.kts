plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

composeCompiler {
    if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// 正式包名（影响 APK 输出文件名）
base {
    // APK 输出文件名。注意：这与 AppUpdateService.APK_NAME（远端 Release 上实际存在的
    // 资产名）是两回事——后者指向已发布的下载地址，改名会让自动更新取不到包，故保持不变。
    archivesName.set("Minexa")
}

android {
    namespace = "com.mineserve.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mineserve.mobile"
        minSdk = 26
        targetSdk = 28
        versionCode = 59
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // ABI 分包：仅打包 arm64-v8a（绝大多数手机），x86_64 模拟器不再产出
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // libfrpcloader.so —— frpc 启动器。
    // 它其实是可执行 ELF，靠 CMake 里的 PREFIX/SUFFIX 伪装成 .so 以便打进
    // lib/<abi>/（nativeLibraryDir 是 Android 允许 execve 的特例）。详见
    // app/src/main/cpp/frpcloader.c 顶部说明。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 发布包固定用同一把密钥签名，保证新旧版本可以互相覆盖安装。
    // 优先读仓库内的 signing/debug.keystore（CI 由 GitHub Secret KEYSTORE_BASE64 解码写回，
    // 见 .github/workflows/build.yml 的 Restore release keystore 步骤），
    // 本地不存在时回退到 ~/.android/debug.keystore。
    // 注意：它是本机生成的唯一密钥（PKCS12，SHA-256 F2:29:D2:…:36），
    // 并非 AOSP 通用调试密钥，私钥不入库。
    signingConfigs {
        getByName("debug") {
            val repoKeystore = rootProject.file("signing/debug.keystore")
            storeFile = if (repoKeystore.exists()) {
                repoKeystore
            } else {
                file("${System.getProperty("user.home")}/.android/debug.keystore")
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 正式包始终启用 R8 混淆与资源压缩。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME / VERSION_CODE（软件更新用）
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}" }
        // 与 AndroidManifest 的 extractNativeLibs="true" 配对。
        // jniLibs 里随包附带的 Termux 依赖库必须被解压成真实文件
        // （nativeLibraryDir/*.so），NativeLibraryBundler 才能复制到 Termux 库目录。
        // 不显式声明的话，未来 Gradle/AGP 升级可能默认改成"压缩存放"，
        // 这些库就只会以 mmap 形式存在，复制不出来，apt/dpkg 又会回到链接失败。
        jniLibs {
            useLegacyPackaging = true
            // 这些是第三方预编译库（取自 Termux bootstrap rootfs），本就已经 strip 过；
            // 再 strip 一次没有收益，却会让构建依赖 NDK 的 llvm-strip（CI 不装 NDK）。
            // 跳过 strip 后，打包结果与 rootfs 里的库逐字节一致，便于比对与排查。
            keepDebugSymbols += "**/*.so"
        }
    }
    lint {
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
    testOptions {
        unitTests {
            // Robolectric 需要真实的资源与 manifest（它要构造一个可用的 Context）。
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.xz)
    implementation(libs.commons.compress)
    implementation(libs.aircompressor)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.apache.ftpserver)
    // 二维码：玩家引导页把「网络名 + 密钥 + 直连地址」编成一张码，玩家扫码即可照抄
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    // Robolectric：让纯 JVM 单测能拿到真实的 android.content.Context，
    // 从而直接实例化 FrpcBackend / FrpcRuntime 这类依赖 Android 的类型。
    // 用于验证「隧道启动是否把网络请求切出主线程」—— 见 FrpcBackendStartThreadTest。
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}
