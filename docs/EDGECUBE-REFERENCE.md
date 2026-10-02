# EdgeCube 参考笔记：网络映射 UI 与 STUN 隧道

来源：<https://github.com/venti1112/EdgeCube>（Flutter + Rust，Dart 侧为主）。
本轮工作只落地 **UI 改版**；STUN 隧道按计划暂缓，实现要点记录在此备查。

## 一、UI 参考：`lib/pages/port_mapping_page.dart`

「网络映射页」= 卡片流，每张卡一个能力，自上而下：

| 卡片 | 关键交互 |
| --- | --- |
| STUN 隧道 | 状态胶囊（运行中/探测中/失败/未启动）+ 总开关 + 公网地址一键复制 + 运行统计 + 端口/并发/保活字段 + 两个子开关 + 保存按钮 + NAT 限制说明 + 日志区 |
| UPnP 映射 | 总开关 + 外网端口 + TCP/UDP 下拉 |
| DDNS | 总开关 + 服务商下拉 + 域名/主机/凭据 + 立即验证按钮 |
| frpc 运行时 | 运行时选择器 + 启停状态胶囊 + 日志区（220dp，等宽 11sp） |

可复用的设计与交互约定：

1. **状态一律「胶囊 + 语义色」**：`失败=error`、`探测中/启动中=onTertiaryContainer`、
   `运行中=primary`、`未启动=outline`。底色用 12% alpha 的圆角 12dp 容器，文字用同色。
   状态胶囊只在功能已启用时出现，未启用不占位。
2. **地址行是「小卡片」而非普通文本**：图标 + 标题（footnote）+ 等宽加粗地址 + 复制按钮，
   整块用 `surfaceContainerHighest` 底。失败时地址置空并换成原因文案。
3. **失败原因单独成条**：errorContainer 底 + warning 图标 + 正文，而不是只改状态文字。
4. **运行统计只在实际运行时出现**：并发 `当前/上限`、实时上下行速率、累计流量，
   三行「图标 + 文本」。不运行时整块隐藏，避免一排 0 占地方。
5. **日志区固定在卡片内**：右上角一键清空，内部滚动，空态居中提示；高度 180–220dp。
6. **危险/前提说明用 info 条**：primary 色 info 图标 + footnote1 文案，
   压在保存按钮下方，说明「本功能只在什么条件下有效」。
7. **配置项改动先落本地 state，点「保存」才写入持久层**（STUN 卡即如此），
   开关类可即时生效。这样用户改到一半不会写坏配置。

## 二、STUN 隧道实现要点（暂缓，备查）

`lib/stun/stun_client.dart`（339 行）+ `lib/stun/stun_tunnel_service.dart`（509 行）
+ `lib/stun/proxy_protocol.dart`（73 行）+ `lib/config/stun_store.dart`（103 行）。

### 核心原理

向 STUN 服务器发 **TCP**（不是 UDP）Binding Request，让 NAT 为本地端口 P 建立映射并
得知其公网端点；随后把 `ServerSocket` 绑到**同一个本地端口 P**，外部连到公网端点即被
NAT 转发进来，再转到 MC 服务端端口。全程无中转，带宽即家宽上行。

**仅对全锥形 NAT（NAT1）有效**；移动网络、CGNAT、对称 NAT 下建不起来，应改用 FRP。

### 三个必须照抄的关键点

1. **必须走 TCP 3478**，且服务器要支持 TCP。常见 STUN 服务器（`stun.l.google.com`、
   `stun.miwifi.com`、`stun.qq.com`、`stun.ekiga.net`）**只支持 UDP**，TCP 连得上但
   永不回包，放进列表只会白等超时。EdgeCube 实网验证过的 TCP 服务器：
   `fwa.lifesizecloud.com`、`stun.freeswitch.org`、`turn.cloudflare.com`、
   `global.turn.twilio.com`、`stun.nextcloud.com`、`stun.telnyx.com`、
   `stun.sonetel.com`、`stun.voip.blackberry.com`。
2. **端口复用是前提**：探测连接连上后必须立刻打 `SO_REUSEADDR`，之后
   `ServerSocket.bind(0.0.0.0, P)` 才可能成功。理由（Linux 内核 bind 冲突检查）：
   已存在的同端口 socket 若处于 `TCP_LISTEN`，新 bind 需双方都带 `SO_REUSEPORT`
   （Dart 的 `ServerSocket.bind` 不设、也无法在 bind 前设）；若处于 `ESTABLISHED`
   且双方都带 `SO_REUSEADDR` 则不冲突，而 `ServerSocket.bind` 在 POSIX 默认设
   `SO_REUSEADDR`。Dart 没有具名常量，按平台硬编码：
   `SO_REUSEADDR = (isLinux || isAndroid) ? 2 : 4`，用 `setRawOption` 打。
3. **保活必须复用同一条探测连接**。MSL（Windows）的做法是每 15 秒用同一源端口新建
   出站连接，在 Linux/Android 上会被内核拒绝（见上）。EdgeCube 改为：探测连接长期存活，
   在其上周期重发 Binding Request——既保活 NAT 映射，又能顺带发现公网地址变化
   （地址变了就重建隧道）。

### 报文编解码（RFC 5389 最小集）

- 20 字节头：`type(2) | length(2) | magic cookie 0x2112A442(4) | txId(12)`。
- Binding Request：`type=0x0001`、`length=0`、magic cookie、随机 12 字节 txId。
- 响应按 txId 匹配在途事务（`Map<hexTxId, Completer>`）；响应类型
  `0x0101` 成功、`0x0111` 错误（解析 `ERROR-CODE 0x0009`，形如 `401 Unauthorized`）。
- 地址属性优先 `XOR-MAPPED-ADDRESS (0x0020)`，回退 `MAPPED-ADDRESS (0x0001)`；
  XOR 版把端口与 magic cookie 高 16 位异或、地址 4 字节依次异或 `0x21 0x12 0xA4 0x42`。
  只接受 IPv4（`family = 0x01`）。属性值按 4 字节对齐。
- 入站数据要**分帧**：先攒够 20 字节读 length，再取 `20+length` 一条，剩余留缓冲；
  缓冲超过 8192 字节说明对端不是 STUN 服务器，直接断开。

### 主循环与容错

`探测（依次试各服务器）→ 绑定监听 → 保活并监控 →（断开/地址变化）拆掉重建`。
连续失败 3 轮即 `failed` 并提示改用 FRP，避免在不支持的网络下无休止重试。
用一个自增 `epoch` 让旧循环自行退出，避免重复启停互相踩踏；延时切成 250ms 片，
以便停止操作能立刻打断。

转发侧：`Socket.connect(127.0.0.1, targetPort, timeout=5s)`，双向 `IOSink.addStream`
搬运（用 addStream 而非逐块 add，对端慢时自动施加背压，不会把数据全堆内存里），
两端都开 `tcpNoDelay`，一边结束就收尾 `destroy`。并发超上限直接 `destroy` 拒掉。

### PROXY protocol v2（`proxy_protocol.dart`）

转发第一批字节前插入 12 字节签名
`0D 0A 0D 0A 00 0D 0A 51 55 49 54 0A` + `0x21`（v2+PROXY）+ `0x11`（TCP over IPv4）
或 `0x21`（TCP over IPv6）+ 2 字节地址长度 + 源地址 + 目的地址 + 源端口 + 目的端口。
IPv4 长度 12、IPv6 长度 36；源与目的地址族不一致时按 IPv6 处理，
IPv4 地址映射为 `::ffff:a.b.c.d`（第 10、11 字节置 0xFF）。

**默认必须关闭**：服务端未开 `proxy_protocol` 时会把这些字节当作协议数据，玩家进不来。

### 配置字段（`stun_store.dart`）

`enabled`、`localPort`（null = 跟随服务端实际端口）、`proxyProtocol`(false)、
`showConnectionLog`(true)、`maxConnections`(128, 1–1024)、`keepAliveSeconds`(20, 5–300)、
`servers`（空 = 内置列表）。持久化为 `config/stun.json`。
