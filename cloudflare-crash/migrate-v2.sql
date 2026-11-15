-- Minexa 崩溃上报 · 增量迁移（已有数据库升级用）
--
-- 用途：把早先建好的 crashes 表补上 occurrences / last_at 两列。
--
-- 用法（在 cloudflare-crash/ 目录下）：
--   npx wrangler d1 execute minexa-crash --remote --file=./migrate-v2.sql
--
-- 这个脚本可以**重复执行**：SQLite 的 ADD COLUMN 重复加会报错，所以这里
-- 用 PRAGMA 判断列是否已存在是做不到的，只能靠"报 duplicate column 就忽略"。
-- 为了简单，这里直接把两条 ALTER 写在文件里，第一次成功、第二次会报错，
-- 那个报错是预期的，不影响数据。

ALTER TABLE crashes ADD COLUMN occurrences INTEGER NOT NULL DEFAULT 1;
ALTER TABLE crashes ADD COLUMN last_at     INTEGER NOT NULL DEFAULT 0;

-- 老数据：把 last_at 补成 occurred_at，避免显示成"1970 年"
UPDATE crashes SET last_at = occurred_at WHERE last_at = 0;
