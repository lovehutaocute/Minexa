# 崩溃自动上报 · 部署说明

**状态：服务端已部署完成并测试通过。** 只差客户端接入。

原计划书里列的这 5 个文件原本并不存在，现在都在这个目录里。

---

## 已经完成的（不用你做了）

| 项目 | 结果 |
|---|---|
| D1 数据库 `minexa-crash` | 已建（id `72517b7b-04ca-462d-a2e8-bc777f1896f8`） |
| 表 `crashes` + 2 个索引 | 已建 |
| Worker `minexa-crash` | 已部署 |
| REPORT_KEY 密钥 | 已设置 |
| **自定义域名** | `https://crash.xn--fjqu03a.top` ✅ 实测可通 |
| 连通性测试 | 上报 / 去重 / 鉴权 / 列表 全部通过 |
| 测试数据 | 已清空 |

### 你的两个地址

| 用途 | 地址 |
|---|---|
| 客户端上报（已写进代码） | `https://crash.xn--fjqu03a.top/report` |
| **你查看崩溃（存书签）** | `https://crash.xn--fjqu03a.top/list?key=9e031481aa4f3e073b6eccd25b9de29d` |

### 重要：为什么不是 workers.dev

部署时 wrangler 打印的是 `https://minexa-crash.hvhvhhbv.workers.dev`。

**这个地址在国内用不了。** 实测：

- DNS 把它解析到 `198.27.124.186` —— 这是个污染/黑洞地址，不是 Cloudflare 真实 IP
- TCP 443 连不上
- 对照组：你现有的 `mcc.xn--fjqu03a.top` 同一时间能正常打开（1095ms）

所以我把同一个 Worker 绑定到了你已有的 `乐子.top` 域名下，得到 `crash.xn--fjqu03a.top`，
实测 1 秒左右可通。

这就是计划书里说的"workers.dev 国内时通时不通"——**不是 bug，是那个域名的固有代价**，
解决办法就是绑自有域名，已经做了。

---

## 你要做的：接入客户端（三步，10 分钟）

### 1. 复制文件

把 `CrashReporter.kt` 复制到：

```
app\src\main\java\com\mineserve\mobile\crash\CrashReporter.kt
```

（`crash` 文件夹要新建）

**地址和密钥已经填好了，不用改。**

### 2. 改 `McApplication.kt`

打开 `app\src\main\java\com\mineserve\mobile\McApplication.kt`。

**先加 import**（顶部 import 区）：

```kotlin
import com.mineserve.mobile.crash.CrashReporter
```

**然后在 `onCreate()` 里加两行。** 位置是**紧跟 `instance = this` 之后**：

```kotlin
override fun onCreate() {
    super.onCreate()
    instance = this

    // ↓↓↓ 加这两行
    CrashReporter.install(this)        // 装全局捕获，捕获之后立即把异常交回系统
    CrashReporter.uploadPending(this)  // 补发上次没发出去的（只排后台任务，不卡启动）
    // ↑↑↑

    // Debug 构建启用 StrictMode：尽早发现主线程磁盘/网络违规
    if (BuildConfig.DEBUG) {
        ...
```

> **为什么改位置**：计划书说"加在 onCreate 最后"。但你的 onCreate 结尾
> （第 303 行前）是一段 `scope().launch { registerBackgroundWork() ... }`，
> 那是"排队等执行"不是"执行完了"，放那儿会跟它抢跑。

### 3. 依赖不用动

| 依赖 | 状态 |
|---|---|
| `androidx.work:work-runtime-ktx` | ✅ 已有 2.10.0 |
| OkHttp | 不需要，用 Java 自带的 `HttpURLConnection` |
| `org.json` | Android 自带 |

**`build.gradle.kts` 一行都不用改。**

---

## 测试

### 3.1 点一下测试崩溃

在任意只在 debug 生效的地方加一行：

```kotlin
if (BuildConfig.DEBUG) throw IllegalStateException("测试崩溃")
```

点一下。**预期：App 正常闪退，弹出"应用已停止运行"** —— 不是黑屏卡死。

> 如果卡死，说明异常没交回系统。检查 `install()` 里最后那句
> `previous?.uncaughtException(...)` 有没有执行到。

### 3.2 让它补发

重新打开 App，等 10~30 秒（WorkManager 后台排队）。然后刷新：

```
https://crash.xn--fjqu03a.top/list?key=9e031481aa4f3e073b6eccd25b9de29d
```

应该多出一条，带版本号和机型。

### 3.3 隐私检查（重要）

点开那条记录看一眼，**里面不应该有**：

- 你自己的服务器目录名
- 世界名
- IP 地址
- 任何文件名

路径应该显示成 `<APP_DATA>`、`<SDCARD>` 这类占位符。

---

## 验收清单

- [ ] App 编译通过
- [ ] 点测试崩溃，App 正常闪退（不是卡死）
- [ ] 重新打开 App 后，`/list` 能看到记录，含版本号和机型
- [ ] 连续崩溃 3 次，库里只有 1 条（去重）
- [ ] 断网崩溃 → 恢复网络 → 下次启动能补发
- [ ] 记录里没有你的私有路径/文件名

---

## 日常维护

### 查看崩溃

```
https://crash.xn--fjqu03a.top/list?key=9e031481aa4f3e073b6eccd25b9de29d
```

存书签。

### JSON 接口（想自己接界面时）

```
https://crash.xn--fjqu03a.top/api/list?key=9e031481aa4f3e073b6eccd25b9de29d&limit=100
```

### 清理 90 天前的数据

```powershell
wrangler d1 execute minexa-crash --remote --command "DELETE FROM crashes WHERE created_at < strftime('%s','now','-90 days')*1000"
```

### 重新部署（改了 worker.js 之后）

```powershell
$env:CLOUDFLARE_API_TOKEN = "你的token"
wrangler deploy
```

> 注意：**改了 worker.js 不会影响已绑定的域名**，Custom Domain 会一直保留。
> 但如果误删了 Worker，域名绑定会跟着消失，要重新绑一次。

---

## 常见问题

### Q: 上报全都没上来

按顺序查：

1. 浏览器打开 `https://crash.xn--fjqu03a.top/health`
   - 打不开 → 域名/Worker 出问题了
   - 能打开 → 继续
2. 打开 `/list?key=...`
   - 说"密钥不对" → `CrashReporter.kt` 里 KEY 填错了
   - 说"服务未配置" → Secret 丢了，重新 `wrangler secret put REPORT_KEY`
3. 看手机日志里有没有 `CrashReporter` 开头的警告

### Q: 为什么不用计划书说的 workers.dev

见上面「重要：为什么不是 workers.dev」。简单说：国内连不上，已换自有域名。

### Q: 密钥泄露了怎么办

```powershell
wrangler secret put REPORT_KEY     # 换一串新的
```

然后改 `CrashReporter.kt` 里 `KEY` 那一行，重新打包发版。

影响上限是"别人能往你库里灌垃圾"，拿不到你任何东西。
**不要为了这个去自建服务器**，那是另一个复杂度级别。

### Q: `/list` 页面太朴素了

能用就行。等你真的天天看，再考虑加筛选/统计。现在先让它工作。

---

## 文件说明

| 文件 | 用途 |
|---|---|
| `worker.js` | 服务端：接收、去重、列表 |
| `schema.sql` | 建表语句（已执行） |
| `wrangler.toml` | 部署配置（database_id 已填） |
| `CrashReporter.kt` | 客户端（地址密钥已填） |
| `test-worker.mjs` | 离线自测，`node test-worker.mjs` 跑，42 项 |
| `deploy.ps1` | 一键部署脚本（**这次没用上，见下方说明**） |

> **关于 `deploy.ps1`**：这个脚本本来是为了帮你省事，但它有个 bug——
> 用 PowerShell 5.1 的 `Set-Content` 回写 `wrangler.toml` 时会按 GBK 编码处理，
> 导致中文注释变乱码。我踩了这个坑，已经修好，但这台机器上部署已经手动完成了，
> 脚本你可以留着以后换账号时用。

---

## 改动这个方案时，请守住三条

1. **隐私白名单**：只发堆栈、版本、机型、系统版本、ABI、线程名。
   不要因为"排查方便"就把服务器名、玩家 ID、世界路径加进去。
2. **不吞异常**：`install()` 里最后必须把异常交回 `previous`，
   否则 App 不闪退而是卡死——比崩溃难查十倍。
3. **上传失败不能影响用户**：断网、超时、5xx 都只留着下次再发，绝不弹窗、绝不阻塞启动。
