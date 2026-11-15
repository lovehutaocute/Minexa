-- Minexa 崩溃上报 · D1 建表
--
-- 用法（在 cloudflare-crash/ 目录下）：
--   npx wrangler d1 execute minexa-crash --remote --file=./schema.sql
--
-- 改动本文件后要重新执行上面的命令才会生效（重复执行是安全的）。

CREATE TABLE IF NOT EXISTS crashes (
  -- 自增主键
  id          INTEGER PRIMARY KEY AUTOINCREMENT,

  -- 去重指纹：sha256(堆栈 + 版本号)。同一个 bug 24 小时内只入库一次
  fingerprint TEXT    NOT NULL,

  -- 版本
  version     TEXT    NOT NULL DEFAULT '',
  versionCode INTEGER NOT NULL DEFAULT 0,

  -- 设备
  model       TEXT    NOT NULL DEFAULT '',
  android     TEXT    NOT NULL DEFAULT '',
  abi         TEXT    NOT NULL DEFAULT '',
  thread      TEXT    NOT NULL DEFAULT '',

  -- 崩溃内容
  stack       TEXT    NOT NULL,

  -- 时间（毫秒时间戳）
  --   occurred_at = 崩溃真正发生的时间（手机上的时间，客户端上报）
  --   created_at  = 服务器收到的时间（用来做清理，不受手机时间错乱影响）
  occurred_at INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,

  -- 这个 bug 一共发生过几次。
  -- 去重时不是简单丢弃，而是把 occurrences 加一、刷新 last_at，
  -- 这样你才能看出"这个崩溃到底是偶发还是每次启动都崩"。
  occurrences INTEGER NOT NULL DEFAULT 1,

  -- 最近一次发生的服务器时间
  last_at     INTEGER NOT NULL DEFAULT 0
);

-- 去重查询：按指纹 + 时间查最近一条
CREATE INDEX IF NOT EXISTS idx_crashes_fingerprint
  ON crashes (fingerprint, created_at DESC);

-- 列表页排序 / 清理用
CREATE INDEX IF NOT EXISTS idx_crashes_created_at
  ON crashes (created_at DESC);
