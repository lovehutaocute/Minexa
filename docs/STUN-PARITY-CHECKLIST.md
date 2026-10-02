# STUN 隧道：EdgeCube 逐项对照核验

参照实现：`/tmp/edgecube-ref/lib/stun/*.dart`（stun_client.dart 339 行、
stun_tunnel_service.dart 509 行、proxy_protocol.dart 73 行、config/stun_store.dart 103 行）
本项目实现：`app/src/main/java/com/mineserve/mobile/server/tunnel/{StunClient,StunTunnelBackend,ProxyProtocolV2}.kt`

## 一、已一致的部分（核验通过）

| 项 | EdgeCube | 本项目 | 结论 |
| --- | --- | --- | --- |
| 探测不绑端口 | `Socket.connect` 无 bind | `connect()` 无 bind | ✅ 已一致 |
| SO_REUSEADDR | `setRawOption(levelSocket, 2/4, 1)` | `setOption(StandardSocketOptions.SO_REUSEADDR, true)` | ✅ 等价 |
| 监听端口 | `session.localPort`（随机 P） | `activeSession.localPort` | ✅ 已一致 |
| 转发目标 | `_targetPort`（MC 端口） | `targetPort` | ✅ 已一致 |
| 报文编解码 | Build Binding / XOR-MAPPED 优先 / 对齐 | 同 | ✅ |
| ERROR-CODE | 0x0009 解析 | 同 | ✅ |
| 内置服务器 | 8 台，TCP 3478 验证过 | 同 8 台 | ✅ |
| PROXY v2 | 12 字节签名 + 0x21/0x11 + 长度 | 同 | ✅ |
| 并发上限拒绝 | `_activeConnections >= maxConnections` | 同 | ✅ |
| 长连接保活 | 探测连接复用为保活连接 | 同 | ✅ |

## 二、缺失项（需补齐）——按优先级

### P0 `_runLoop` 重试与自动重建（缺失最严重）
EdgeCube `stun_tunnel_service.dart:199-251`：
- 探测失败 → 计数 `failures++`，**15 秒后重试**，最多 `_maxConsecutiveFailures = 3` 轮
- 监听失败 → 计数，**5 秒后重试**，并且把 `_publicAddress` 清空（不留假地址在界面）
- 成功后 `failures = 0`
- `_keepAlive` 返回（映射失效）→ `_teardown()` → 状态回 `probing` → 日志「正在重建隧道…」→ 3 秒后再来一轮
- 本项目目前：一次失败即 `Result.failure`，`TunnelStatus.Failed` 终止，无重试、无重建。

### P1 `_pump` 背压
EdgeCube `:404-421` 用 `to.addStream(from.map(...))`，对端慢时自动背压，避免大流量堆积内存。
本项目 `pump()` 是 `input.read(buf)` + `output.write/flush` 的逐块循环，无背压。
另外 EdgeCube 用 `Future.any([pump(in,out), pump(out,in)])` 等待任一方向结束；
本项目用两个线程 + `up.join(); down.join()`（join down 可能长时间阻塞）。

### P2 速率统计（uploadSpeed / downloadSpeed）
EdgeCube 每秒 `_tickSpeed()` 计算 `_totalUpload - _lastUpload`，界面显示实时速率。
本项目只上报累计字节，`TunnelState` 里的 `uploadBytesPerSecond/downloadBytesPerSecond` 未填。

### P3 `_probe` 里的 epoch 检查点
EdgeCube 在 `connect` 之后、`requestMapping` 之后各查一次 epoch，及时退出并 `close()`。
本项目只在 for 循环开头查 `generation`，单台服务器探测期间不检查。

### P4 `_teardown` 用 Set 管理活跃 socket
EdgeCube `_liveSockets` 是 `Set<Socket>`，入站与后端 socket 都加入，`_teardown` 统一 destroy。
本项目用 `forwardSockets`（CopyOnWriteArrayList）但 handleConnection 结束时清理不够干净。

### P5 日志环形缓冲 + 回放
EdgeCube `_maxLogLines = 500` 环形缓冲，`logs()` 新订阅者先收到历史回放。
本项目直接走 `termux.emitLog`，无缓冲（依赖既有控制台）。

### P6 `clearLog()`

## 三、下一步

按 P0 → P1 → P2 顺序补齐。P0 是本轮核心：没有重试与重建，
在 NAT 抖动或 STUN 服务器临时不可用时隧道会直接死掉，而 EdgeCube 能自愈。

关键常量：`_maxConsecutiveFailures = 3`、探测失败重试 15s、监听失败重试 5s、重建间隔 3s。

---

## 四、用户决定（本轮）

- 范围：**P0~P6 全部一次补齐**
- 验证：**只保证编译通过 + 构建 APK**（无实机验证）

## 五、改造方案（`StunTunnelBackend.kt` 重写为常驻循环）

当前结构：`start()` → `doStart()` 一次性探测+监听，失败即 `Result.failure`。
目标结构（对照 EdgeCube `_runLoop`）：

```
start(config) -> Result<Unit>:
  递增 generation；置 probing 状态；清零统计；启动每秒速率定时器
  起一个后台线程跑 runLoop(myGeneration)
  立即返回 Result.success（与 EdgeCube 一致：start 不等待就绪）

runLoop(generation):
  failures = 0
  while (running && generation 匹配):
    session = probe(generation)                 // P3: 内部在 connect 后与 requestMapping 后各查一次
    if (session == null):
      failures++
      if (failures >= 3) { fail("全部 STUN 服务器均无法建立隧道…改为 FRP"); return }
      log("本轮探测失败，15 秒后重试 ($failures/3)"); 状态=probing; sleep(15s); continue
    bound = bindListener(session, generation)   // 绑 session.localPort
    if (!bound):
      failures++; session.close(); published=null
      if (failures >= 3) { fail("无法在映射端口上建立监听，隧道启动失败"); return }
      sleep(5s); continue
    failures = 0
    keepAlive(session, generation)              // 返回即表示需重建
    if (generation 变了) break
    teardown(); 状态=probing; published=null
    log("正在重建隧道…"); sleep(3s)
```

关键常量：`MAX_CONSECUTIVE_FAILURES = 3`、探测失败重试 15s、监听失败重试 5s、重建间隔 3s。

## 六、其余各项改法

- **P1**：`pump` 改为带背压；`handleConnection` 用「等任一方向结束」而非 `up.join()+down.join()`。
- **P2**：新增每秒定时线程算 `speed = total - last`，写入 `TunnelState` 的
  `uploadBytesPerSecond` / `downloadBytesPerSecond`。
- **P3**：`probe` 内在 `connect` 后、`requestMapping` 后各查一次 `generation`，过期即 `close()` 返回 null。
- **P4**：把 `forwardSockets: CopyOnWriteArrayList` 换成 `Collections.newSetFromMap(ConcurrentHashMap())`，
  入站与后端 socket 都登记，`teardown` 统一销毁。
- **P5**：`StunTunnelBackend` 内建 500 行环形日志缓冲 `ArrayDeque`，`log()` 同时写缓冲与 `logSink`。
- **P6**：加 `clearLog()`；UI 的「隧道日志」区可接一个清除按钮（可选）。

## 七、待确认的接口影响

- `start()` 改为「立即返回、异步就绪」后，`McViewModel.startTunnel()` 里
  `when (st.status)` 的 `Running ->` 分支不会再立即命中（状态先是 Starting），
  会走到 `else -> s205`。需改为提示「正在探测，请稍候」或监听状态流转。
  相关：`app/src/main/java/com/mineserve/mobile/ui/McViewModel.kt:2207-2237`。
