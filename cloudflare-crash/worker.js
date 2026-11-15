/**
 * Minexa 崩溃上报 Worker（独立部署，与 cloudflare/ 的使用统计互不影响）
 *
 * 接口：
 *   POST /report   { version, versionCode, model, android, abi, thread, stack, occurredAt }
 *                  请求头 X-Key: <REPORT_KEY>
 *                  -> { ok: true, duplicate: false }   新记录已入库
 *                  -> { ok: true, duplicate: true }     24 小时内同一个 bug，已忽略
 *   GET  /login    登录页；POST /login?key=... 验证密码并写 Cookie
 *   GET  /logout   清 Cookie
 *   GET  /list     查看页（需要登录 Cookie），支持 q / version / model / android / page
 *   GET  /api/list 同上，返回 JSON
 *   GET  /health   不需要密钥，用来确认服务活着
 *
 * 安全模型（按需求："只要不疯狂上传、不全量删除，就够用"）：
 *
 *   1. **没有删除接口**。整个 Worker 不提供任何 DELETE，要删数据只能你自己用
 *      `wrangler d1 execute` 从本机操作。这是最硬的防护——接口不存在就无从攻击。
 *   2. **上报限流**：同一 IP 每分钟有上限，另有全天总量上限，防止被刷爆存储。
 *   3. **查询也要登录**：密码不放在网址里（那样的密码会进浏览器历史、Referer、
 *      服务器日志），改为验证成功后写一个 HttpOnly Cookie，30 天内免登录。
 *      这样即使有人拿到了页面链接，没有 Cookie 也看不到任何数据。
 *   4. **登录限流**：按 IP 计失败次数，错了就计数，防止脚本无限尝试。
 *   5. 所有输出到页面的内容都做 HTML 转义；响应带 noindex，不进搜索引擎。
 *
 * Secrets（用 npx wrangler secret put 设置）：
 *   REPORT_KEY  上报密钥（客户端 APK 里会有，只能写不能读）
 *   VIEW_PASSWORD 查看密码（只有你知道，不随 APK 分发）
 *   两个都没设置时，对应接口直接拒绝（fail-closed）。
 *   VIEW_PASSWORD 没设置时会回退用 REPORT_KEY，避免旧的部署方式失效。
 *
 * 设计取舍：
 *   - REPORT_KEY 会随 APK 被反编译看到，这是公开客户端的固有属性。影响上限是
 *     "别人能往你库里灌垃圾"，配合限流后这个上限也很小。真被灌了就换密钥重发版。
 *   - 去重是"先查再插"，并发时可能漏判一次。这个量级（一天几十条）完全够用；
 *     要做到严格去重得上 Durable Object，不值当。
 *   - 限流计数存在 KV 里，KV 是最终一致的，所以限流是"软限制"：能挡住脚本狂刷，
 *     挡不住精心构造的分布式攻击。按当前的暴露面，这个取舍是合适的。
 */

/** 上报用的密钥头 */
const KEY_HEADER = 'X-Key';

/** 登录成功后写的 Cookie 名与有效期 */
const VIEW_COOKIE = 'minexa_crash_view';
const VIEW_COOKIE_DAYS = 30;

/** 列表分页 */
const DEFAULT_PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 200;

/** 去重窗口：同一个 bug 24 小时内只入库一次 */
const DEDUPE_WINDOW_MS = 24 * 60 * 60 * 1000;

/** 字段长度上限，防止有人塞巨大的字符串把数据库撑爆 */
const MAX_STACK_LEN = 16 * 1024;
const MAX_SHORT_LEN = 200;

/** 上报限流：同一 IP 每分钟 / 每天 */
const MAX_REPORT_PER_MINUTE = 20;
const MAX_REPORT_PER_DAY = 200;

/** 登录失败限流：同一 IP 每 10 分钟最多试几次 */
const MAX_LOGIN_FAIL_PER_WINDOW = 10;
const LOGIN_WINDOW_MS = 10 * 60 * 1000;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    try {
      if (request.method === 'OPTIONS') {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }

      if (path === '/health') {
        return json({ ok: true, time: Date.now() });
      }

      if (path === '/report') {
        return await handleReport(request, env);
      }

      if (path === '/login') {
        return await handleLogin(request, url, env);
      }

      if (path === '/logout') {
        return handleLogout();
      }

      if (path === '/list') {
        return await handleList(request, url, env);
      }

      if (path === '/api/list') {
        return await handleApiList(request, url, env);
      }

      return new Response('Not Found', { status: 404, headers: corsHeaders() });
    } catch (err) {
      // 宁可返回 500 也不要把异常细节吐给公网
      console.error('worker error:', err && err.stack ? err.stack : err);
      return json({ ok: false, error: 'internal error' }, 500);
    }
  },
};

// ---------------------------------------------------------------------------
// POST /report
// ---------------------------------------------------------------------------

async function handleReport(request, env) {
  if (request.method !== 'POST') {
    return json({ ok: false, error: 'method not allowed' }, 405);
  }

  // 密钥没配就直接拒绝：宁可全都不收，也不要留一个"谁都能写"的口子
  const expected = env.REPORT_KEY;
  if (!expected) {
    return json({ ok: false, error: 'server not configured' }, 503);
  }
  const given = request.headers.get(KEY_HEADER) || '';
  if (!timingSafeEqual(given, expected)) {
    return json({ ok: false, error: 'forbidden' }, 403);
  }

  const db = env.DB;
  if (!db) {
    return json({ ok: false, error: 'database not bound' }, 503);
  }

  // 两层限流：分钟级挡"脚本狂刷"，天级挡"慢慢灌满存储"。
  // 没绑定 RATE 时跳过（本地测试 / 还没建 KV），不影响正常上报。
  const ip = clientIp(request);
  if (env.RATE) {
    const perMinute = await consumeQuota(env.RATE, 'rl:report:m:' + ip, MAX_REPORT_PER_MINUTE, 60_000);
    if (!perMinute) return json({ ok: false, error: 'too many requests' }, 429);
    const perDay = await consumeQuota(env.RATE, 'rl:report:d:' + ip, MAX_REPORT_PER_DAY, 24 * 3600_000);
    if (!perDay) return json({ ok: false, error: 'daily quota exceeded' }, 429);
  }

  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json({ ok: false, error: 'invalid json' }, 400);
  }
  if (!body || typeof body !== 'object') {
    return json({ ok: false, error: 'invalid body' }, 400);
  }

  const stack = str(body.stack, MAX_STACK_LEN);
  if (!stack) {
    return json({ ok: false, error: 'stack is required' }, 400);
  }

  const version = str(body.version, MAX_SHORT_LEN);
  const versionCode = toInt(body.versionCode);
  const model = str(body.model, MAX_SHORT_LEN);
  const android = str(body.android, MAX_SHORT_LEN);
  const abi = str(body.abi, MAX_SHORT_LEN);
  const thread = str(body.thread, MAX_SHORT_LEN);
  const occurredAt = toInt(body.occurredAt) || Date.now();
  const now = Date.now();

  // 指纹只取"堆栈前几行 + 版本号"：堆栈末尾常带内存地址/随机数，全量参与去重会失效
  const fingerprint = await sha256(
    versionCode + '\n' + normalizeStackForFingerprint(stack)
  );

  // 24 小时内同指纹已存在 → 不新增记录，而是**累加计数**。
  //
  // 为什么不直接丢弃：光看"有没有这条"分不清"偶发一次"和"每次启动都崩"，
  // 后者才是真正要紧急修的。所以这里把 occurrences 加一、刷新 last_at，
  // 并用最新一次的堆栈覆盖旧值——同一指纹下行号/内存地址可能不同，
  // 留最新的更贴近现状，也方便你看到最近一次崩在哪一行。
  const existing = await db
    .prepare('SELECT id FROM crashes WHERE fingerprint = ? AND created_at > ? LIMIT 1')
    .bind(fingerprint, now - DEDUPE_WINDOW_MS)
    .first();

  if (existing) {
    await db
      .prepare(
        `UPDATE crashes
            SET occurrences = occurrences + 1,
                last_at = ?,
                stack = ?,
                occurred_at = ?,
                model = ?,
                android = ?,
                abi = ?,
                thread = ?
          WHERE id = ?`
      )
      .bind(now, stack, occurredAt, model, android, abi, thread, existing.id)
      .run();
    return json({ ok: true, duplicate: true });
  }

  await db
    .prepare(
      `INSERT INTO crashes
         (fingerprint, version, versionCode, model, android, abi, thread, stack, occurred_at, created_at, occurrences, last_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`
    )
    .bind(fingerprint, version, versionCode, model, android, abi, thread, stack, occurredAt, now, now)
    .run();

  return json({ ok: true, duplicate: false });
}

// ---------------------------------------------------------------------------
// 登录 / 登出
// ---------------------------------------------------------------------------

/** 查看用的密码：优先 VIEW_PASSWORD，没配则回退 REPORT_KEY（兼容旧部署） */
function viewPassword(env) {
  return env.VIEW_PASSWORD || env.REPORT_KEY || '';
}

async function handleLogin(request, url, env) {
  const password = viewPassword(env);
  if (!password) {
    return html(notConfiguredPage(), 503);
  }

  // GET /login：只是显示输入框
  if (request.method === 'GET') {
    const next = url.searchParams.get('next') || '/list';
    return html(loginPage(safeNext(next), url.searchParams.get('error')));
  }

  if (request.method !== 'POST') {
    return json({ ok: false, error: 'method not allowed' }, 405);
  }

  // 登录限流：失败次数计数。先扣配额再校验，失败也要计数，
  // 否则可以无限次试错直到蒙对。
  const ip = clientIp(request);
  if (env.RATE) {
    const allowed = await consumeQuota(
      env.RATE,
      'rl:login:' + ip,
      MAX_LOGIN_FAIL_PER_WINDOW,
      LOGIN_WINDOW_MS,
      { countOnSuccess: false }
    );
    if (!allowed) {
      return html(loginPage('/list', '尝试太频繁，请等 10 分钟后再试'), 429);
    }
  }

  // 密码可以来自表单，也可以来自查询参数（方便命令行 curl 调用）
  let given = url.searchParams.get('key') || '';
  if (!given) {
    try {
      const form = await request.formData();
      given = form.get('password') || '';
    } catch (e) {
      given = '';
    }
  }

  if (!timingSafeEqual(String(given), password)) {
    return html(loginPage('/list', '密码不对'), 403);
  }

  const next = safeNext(url.searchParams.get('next') || '/list');
  const maxAge = VIEW_COOKIE_DAYS * 24 * 3600;
  return new Response(null, {
    status: 302,
    headers: {
      Location: next,
      // HttpOnly：JS 读不到，降低被 XSS 偷走的可能
      // Secure：只走 HTTPS
      // SameSite=Lax：别的站点跳转过来时不带，防 CSRF
      'Set-Cookie': `${VIEW_COOKIE}=${encodeURIComponent(password)}; Path=/; Max-Age=${maxAge}; HttpOnly; Secure; SameSite=Lax`,
      'Cache-Control': 'no-store',
    },
  });
}

function handleLogout() {
  return new Response(null, {
    status: 302,
    headers: {
      Location: '/login',
      'Set-Cookie': `${VIEW_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax`,
      'Cache-Control': 'no-store',
    },
  });
}

/** 校验查看权限。通过返回 null，不通过返回一个响应 */
function requireView(request, url, env) {
  const password = viewPassword(env);
  if (!password) {
    return html(notConfiguredPage(), 503);
  }

  // 1) Cookie（浏览器正常访问）
  const cookie = parseCookie(request.headers.get('Cookie') || '', VIEW_COOKIE);
  if (cookie && timingSafeEqual(cookie, password)) return null;

  // 2) 查询参数（命令行 / 脚本调用）
  const fromQuery = url.searchParams.get('key');
  if (fromQuery && timingSafeEqual(fromQuery, password)) return null;

  // 没有权限：浏览器给登录页，脚本给 401 JSON
  const wantsJson = url.pathname.startsWith('/api/');
  if (wantsJson) {
    return json({ ok: false, error: 'unauthorized' }, 401);
  }
  return new Response(null, {
    status: 302,
    headers: { Location: '/login?next=' + encodeURIComponent(url.pathname + url.search), 'Cache-Control': 'no-store' },
  });
}

// ---------------------------------------------------------------------------
// 查询：/list 与 /api/list
// ---------------------------------------------------------------------------

/** 从 URL 解析查询条件 */
function parseFilters(url) {
  const q = (url.searchParams.get('q') || '').trim();
  const version = (url.searchParams.get('version') || '').trim();
  const model = (url.searchParams.get('model') || '').trim();
  const android = (url.searchParams.get('android') || '').trim();

  const pageSizeRaw = Number(url.searchParams.get('pageSize'));
  const pageSize = Number.isFinite(pageSizeRaw) && pageSizeRaw > 0
    ? Math.min(Math.trunc(pageSizeRaw), MAX_PAGE_SIZE)
    : DEFAULT_PAGE_SIZE;

  const pageRaw = Number(url.searchParams.get('page'));
  const page = Number.isFinite(pageRaw) && pageRaw > 0 ? Math.trunc(pageRaw) : 1;

  return { q, version, model, android, page, pageSize };
}

/** 把过滤条件拼成 SQL 的 WHERE 子句与参数 */
function buildWhere(f) {
  const clauses = [];
  const params = [];
  if (f.q) {
    clauses.push('stack LIKE ?');
    params.push('%' + f.q + '%');
  }
  if (f.version) {
    clauses.push('version = ?');
    params.push(f.version);
  }
  if (f.model) {
    clauses.push('model = ?');
    params.push(f.model);
  }
  if (f.android) {
    clauses.push('android = ?');
    params.push(f.android);
  }
  return {
    where: clauses.length ? ' WHERE ' + clauses.join(' AND ') : '',
    params,
  };
}

/**
 * 早先的版本是"分组统计 occurrences"，现在次数直接存在表里（去重时累加），
 * 所以这里只需保证字段存在、类型正确，不再需要额外查询。
 */
function normalizeOccurrences(rows) {
  return rows.map((r) => ({
    ...r,
    occurrences: Number(r.occurrences) || 1,
    last_at: Number(r.last_at) || Number(r.occurred_at) || 0,
  }));
}

/** 拿到筛选下拉框的候选值 */
async function loadFacets(db) {
  try {
    const [v, m, a] = await Promise.all([
      db.prepare('SELECT DISTINCT version FROM crashes ORDER BY version DESC LIMIT 50').all(),
      db.prepare('SELECT DISTINCT model FROM crashes ORDER BY model LIMIT 50').all(),
      db.prepare('SELECT DISTINCT android FROM crashes ORDER BY android DESC LIMIT 50').all(),
    ]);
    return {
      versions: (v.results || []).map((r) => r.version).filter(Boolean),
      models: (m.results || []).map((r) => r.model).filter(Boolean),
      androids: (a.results || []).map((r) => r.android).filter(Boolean),
    };
  } catch (e) {
    return { versions: [], models: [], androids: [] };
  }
}

async function queryCrashes(db, f) {
  const { where, params } = buildWhere(f);
  const offset = (f.page - 1) * f.pageSize;

  const totalRow = await db
    .prepare('SELECT COUNT(*) AS n FROM crashes' + where)
    .bind(...params)
    .first();
  const total = totalRow && typeof totalRow.n === 'number' ? totalRow.n : 0;

  const { results } = await db
    .prepare(
      `SELECT id, fingerprint, version, versionCode, model, android, abi, thread,
              stack, occurred_at, created_at, occurrences, last_at
         FROM crashes${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`
    )
    .bind(...params, f.pageSize, offset)
    .all();

  const rows = normalizeOccurrences(results || []);
  return { rows, total, page: f.page, pageSize: f.pageSize };
}

async function handleApiList(request, url, env) {
  const denied = requireView(request, url, env);
  if (denied) return denied;

  const db = env.DB;
  if (!db) return json({ ok: false, error: 'database not bound' }, 503);

  const f = parseFilters(url);
  const { rows, total, page, pageSize } = await queryCrashes(db, f);
  return json({ ok: true, total, page, pageSize, crashes: rows });
}

async function handleList(request, url, env) {
  const denied = requireView(request, url, env);
  if (denied) return denied;

  const db = env.DB;
  if (!db) {
    return html('<h1>数据库未绑定</h1><p>检查 wrangler.toml 的 d1_databases 配置。</p>', 503);
  }

  const f = parseFilters(url);
  const [{ rows, total, page, pageSize }, facets] = await Promise.all([
    queryCrashes(db, f),
    loadFacets(db),
  ]);

  return html(renderListPage(rows, total, page, pageSize, f, facets));
}

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

const BASE_CSS = `
  * { box-sizing: border-box; }
  body { margin:0; padding:20px; background:#0f172a; color:#e2e8f0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
  a { color:#818cf8; }
  h1 { font-size:20px; margin:0 0 4px; color:#fff; }
  .sub { color:#94a3b8; font-size:13px; margin-bottom:16px; }
  input, select, button {
    font-family: inherit; font-size:13px; padding:7px 10px; border-radius:8px;
    border:1px solid rgba(255,255,255,.15); background:rgba(255,255,255,.06); color:#e2e8f0; }
  button { cursor:pointer; }
  button:hover { background:rgba(255,255,255,.12); }
  .crash { background:rgba(255,255,255,.05); border:1px solid rgba(255,255,255,.1);
    border-radius:12px; padding:14px 16px; margin-bottom:12px; }
  .crash header { display:flex; flex-wrap:wrap; gap:8px; align-items:baseline; margin-bottom:6px; }
  .ver { background:#4f46e5; color:#fff; font-size:12px; padding:3px 10px; border-radius:999px; font-weight:600; }
  .dev { color:#cbd5e1; font-size:13px; }
  .cnt { background:#b91c1c; color:#fff; font-size:12px; padding:3px 9px; border-radius:999px; font-weight:600; }
  .meta { color:#64748b; font-size:12px; margin-bottom:10px; }
  pre { background:#020617; border-radius:8px; padding:12px; margin:0; overflow-x:auto;
    font-size:12px; line-height:1.6; color:#fca5a5;
    font-family: ui-monospace, SFMono-Regular, Consolas, monospace; white-space:pre-wrap; word-break:break-all; }
  .empty { color:#64748b; }
  @media (max-width:600px) { body { padding:12px; } pre { font-size:11px; } }
`;

function loginPage(next, error) {
  return `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="robots" content="noindex, nofollow">
<title>Minexa · 登录</title>
<style>${BASE_CSS}
  .box { max-width:360px; margin:12vh auto; background:rgba(255,255,255,.05);
    border:1px solid rgba(255,255,255,.1); border-radius:16px; padding:28px; }
  input[type=password] { width:100%; margin-bottom:12px; }
  button[type=submit] { width:100%; background:#4f46e5; border-color:#4f46e5; font-weight:600; }
  button[type=submit]:hover { background:#6366f1; }
  .err { color:#f87171; font-size:13px; margin-bottom:12px; }
</style></head>
<body>
  <div class="box">
    <h1>崩溃记录</h1>
    <div class="sub">输入查看密码</div>
    ${error ? `<div class="err">${esc(error)}</div>` : ''}
    <form method="POST" action="/login?next=${encodeURIComponent(next)}">
      <input type="password" name="password" placeholder="密码" autofocus autocomplete="current-password">
      <button type="submit">进入</button>
    </form>
  </div>
</body></html>`;
}

function notConfiguredPage() {
  return `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="robots" content="noindex, nofollow"><title>未配置</title>
<style>${BASE_CSS}</style></head>
<body><h1>服务未配置</h1>
<p>还没有设置查看密码。在你的电脑上执行：</p>
<pre>npx wrangler secret put VIEW_PASSWORD</pre>
<p>（没有设置时会回退用 REPORT_KEY）</p>
</body></html>`;
}

function renderListPage(rows, total, page, pageSize, f, facets) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const qs = (over = {}) => {
    const p = new URLSearchParams();
    const merged = { q: f.q, version: f.version, model: f.model, android: f.android, ...over };
    for (const [k, v] of Object.entries(merged)) {
      if (v) p.set(k, v);
    }
    const s = p.toString();
    return '/list' + (s ? '?' + s : '');
  };

  const options = (list, current) =>
    ['<option value="">全部</option>']
      .concat(list.map((v) => `<option value="${esc(v)}"${v === current ? ' selected' : ''}>${esc(v)}</option>`))
      .join('');

  const items = rows
    .map((r, idx) => {
      const occurred = r.occurred_at ? fmtTime(r.occurred_at) : '未知';
      const received = fmtTime(r.created_at);
      const stackLines = String(r.stack || '').split('\n');
      const shown = stackLines.slice(0, 40).join('\n');
      const more = stackLines.length > 40 ? `\n……（还有 ${stackLines.length - 40} 行）` : '';
      const occ = Number(r.occurrences) > 1
        ? `<span class="cnt">×${r.occurrences}</span>`
        : '';
      return `
      <article class="crash">
        <header>
          <span class="ver">v${esc(r.version || '?')} (${r.versionCode || 0})</span>
          ${occ}
          <span class="dev">${esc(r.model || '未知机型')} · Android ${esc(r.android || '?')} · ${esc(r.abi || '?')}</span>
        </header>
        <div class="meta">
          最近 ${esc(occurred)} · 收到 ${esc(received)}
          ${r.thread ? ' · 线程 ' + esc(r.thread) : ''}
          ${r.last_at && r.occurrences > 1 ? ' · 最后发生 ' + esc(fmtTime(r.last_at)) : ''}
        </div>
        <pre id="stack-${idx}">${esc(shown + more)}</pre>
        <button onclick="copyStack(${idx}, this)">复制堆栈</button>
      </article>`;
    })
    .join('\n');

  const empty = rows.length === 0
    ? `<p class="empty">${total === 0 ? '还没有任何崩溃记录。' : '当前筛选条件下没有记录。'}</p>`
    : '';

  const pager = totalPages > 1
    ? `<div style="display:flex;gap:10px;align-items:center;justify-content:center;margin:18px 0;">
         ${page > 1 ? `<a href="${esc(qs({ page: page - 1 }))}">← 上一页</a>` : ''}
         <span class="sub" style="margin:0;">第 ${page} / ${totalPages} 页</span>
         ${page < totalPages ? `<a href="${esc(qs({ page: page + 1 }))}">下一页 →</a>` : ''}
       </div>`
    : '';

  return `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="robots" content="noindex, nofollow">
<title>Minexa · 崩溃记录</title>
<style>${BASE_CSS}
  .filters { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:18px; align-items:center; }
  .filters input[type=search] { flex:1 1 200px; min-width:160px; }
  .spacer { flex:1; }
</style></head>
<body>
  <h1>崩溃记录</h1>
  <div class="sub">
    共 ${total} 条${f.q || f.version || f.model || f.android ? '（已筛选）' : ''} ·
    本页 ${rows.length} 条 ·
    <a href="/logout">退出登录</a>
  </div>

  <form class="filters" method="GET" action="/list">
    <input type="search" name="q" placeholder="搜索堆栈关键词，如 NullPointerException" value="${esc(f.q)}">
    <select name="version" onchange="this.form.submit()">${options(facets.versions, f.version)}</select>
    <select name="model" onchange="this.form.submit()">${options(facets.models, f.model)}</select>
    <select name="android" onchange="this.form.submit()">${options(facets.androids, f.android)}</select>
    <button type="submit">搜索</button>
    ${(f.q || f.version || f.model || f.android) ? '<a href="/list">清空筛选</a>' : ''}
  </form>

  ${empty}
  ${items}
  ${pager}

  <script>
    function copyStack(idx, btn) {
      var el = document.getElementById('stack-' + idx);
      var text = el.textContent;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          var old = btn.textContent;
          btn.textContent = '已复制';
          setTimeout(function () { btn.textContent = old; }, 1500);
        }).catch(function () { fallback(text, btn); });
      } else {
        fallback(text, btn);
      }
    }
    function fallback(text, btn) {
      // 老浏览器 / 非 HTTPS 场景下的兜底：选中文本并提示手动复制
      var ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); btn.textContent = '已复制'; }
      catch (e) { btn.textContent = '请手动复制'; }
      document.body.removeChild(ta);
      setTimeout(function () { btn.textContent = '复制堆栈'; }, 1500);
    }
  </script>
</body></html>`;
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...corsHeaders(), 'Content-Type': 'application/json; charset=utf-8' },
  });
}

function html(body, status = 200) {
  return new Response(body, {
    status,
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      // 页面里会出现崩溃堆栈，不允许被搜索引擎收录
      'X-Robots-Tag': 'noindex, nofollow',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'X-Frame-Options': 'DENY',
    },
  });
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, ' + KEY_HEADER,
    'Cache-Control': 'no-store',
  };
}

/** 取字符串并截断到上限；非字符串一律当空 */
function str(v, maxLen) {
  if (typeof v !== 'string') return '';
  const s = v.trim();
  return s.length > maxLen ? s.slice(0, maxLen) : s;
}

/** 取整数；坏值当 0 */
function toInt(v) {
  const n = Math.trunc(Number(v));
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

/** 只允许跳到本站路径，防止被构造成钓鲺跳转 */
function safeNext(raw) {
  if (typeof raw !== 'string') return '/list';
  if (!raw.startsWith('/')) return '/list';
  if (raw.startsWith('//')) return '/list';
  if (raw.startsWith('/\\')) return '/list';
  return raw;
}

function parseCookie(header, name) {
  for (const part of header.split(';')) {
    const idx = part.indexOf('=');
    if (idx === -1) continue;
    const k = part.slice(0, idx).trim();
    if (k === name) {
      try {
        return decodeURIComponent(part.slice(idx + 1).trim());
      } catch (e) {
        return part.slice(idx + 1).trim();
      }
    }
  }
  return null;
}

/**
 * 去重用的堆栈摘要。
 *
 * 这一段直接决定"去重准不准"，是线上最容易出问题的地方，所以说明白：
 *
 * 一次同样的崩溃，两次上报的堆栈**往往不是逐字相同的**，常见的差异有：
 *   - 内存地址：      0xdeadbeef / 0x1234          → 每次都不一样
 *   - 行号：          Foo.kt:42 → Foo.kt:45        → 发版后会变
 *   - lambda 编号：   Foo$bar$1.invoke → $2        → 不稳定
 *   - 匿名内部类数字：Foo$1 / Foo$2                 → 不稳定
 *   - 运行时/框架帧： androidx、kotlin、java 的帧   → 同一 bug 在不同设备上可能多一帧少一帧
 *
 * 所以做法是：
 *   1. **第一行只留异常类型名**（消息里常带用户路径，绝不参与指纹）；
 *   2. 只保留 at 行的「包.类.方法」骨架，括号里的文件名和行号全丢；
 *   3. 丢掉 androidx/kotlin/java/android 等框架帧——它们不是你的代码，却是最会变的部分；
 *   4. 最多取前 5 帧（第一行 + 4 个业务帧）。
 *
 * 这样同一个 bug 无论发版前后、换台手机，都能归到一起；
 * 而不同 bug 因为类方法名不同，不会被误合并（见 test-worker.mjs 的 [3b]）。
 */
function normalizeStackForFingerprint(stack) {
  const lines = String(stack).split('\n').map((l) => l.trim()).filter(Boolean);
  if (lines.length === 0) return '';

  const NOISE = [
    'at androidx.', 'at kotlin.', 'at kotlinx.', 'at java.', 'at javax.',
    'at android.', 'at dalvik.', 'at libcore.', 'at com.android.',
  ];

  const out = [];
  let head = lines[0].replace(/^((?:Caused by|Suppressed):\s*)?([\w.$]+):.*$/, '$1$2');
  head = head.replace(/\$\d+/g, '');
  out.push(head);

  for (const raw of lines.slice(1)) {
    let s = raw;
    s = s.replace(/0x[0-9a-fA-F]+/g, '');
    s = s.replace(/\(.*$/, '');
    s = s.replace(/\$\d+/g, '');
    s = s.replace(/\s+/g, ' ').trim();
    if (!s) continue;

    const isAt = s.startsWith('at ');
    if (isAt && NOISE.some((p) => s.startsWith(p))) continue;
    if (!isAt) {
      s = s.replace(/^((?:Caused by|Suppressed):\s*)?([\w.$]+):.*$/, '$1$2');
    }
    out.push(s);
    if (out.length >= 5) break;
  }

  return out.join('\n');
}

async function sha256(text) {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Cloudflare 把真实来源 IP 放在这个头里 */
function clientIp(request) {
  return (request.headers.get('CF-Connecting-IP') || 'unknown').trim();
}

/** 近似定长时间比较，避免"第几个字符先不同"泄露信息 */
function timingSafeEqual(a, b) {
  const enc = new TextEncoder();
  const x = enc.encode(a || '');
  const y = enc.encode(b || '');
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) {
    diff |= (x[i] || 0) ^ (y[i] || 0);
  }
  return diff === 0 && x.length > 0;
}

/**
 * 简易限流：KV 计数 + TTL 窗口。
 *
 * KV 是最终一致的，并发时可能同时通过，上界不是硬保证——
 * 但足以把"脚本狂刷"从无限次压到每分钟固定次数。没有 KV 绑定时直接放行。
 *
 * opts.countOnSuccess=false 时用于"失败才计数"的场景……实际上这里仍是每次调用都计数，
 * 因为提前计数能防住"无限试错"，代价是正常用户也会消耗配额。对登录来说这个取舍是对的。
 */
async function consumeQuota(kv, key, limit, windowMs) {
  const now = Date.now();
  let raw = null;
  try {
    raw = await kv.get(key, 'json');
  } catch (e) {
    return true; // KV 出错时宁可放行，也不要让正常人被误拦
  }
  let count = 0;
  if (raw && typeof raw.count === 'number' && raw.resetAt > now) count = raw.count;
  if (count >= limit) return false;
  try {
    await kv.put(key, JSON.stringify({ count: count + 1, resetAt: now + windowMs }), {
      expirationTtl: Math.ceil(windowMs / 1000),
    });
  } catch (e) {
    // 写失败就算了，不影响这次请求
  }
  return true;
}

/** HTML 转义：崩溃堆栈里可能有 < > & 和引号，不转义会把页面搞乱 */
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 时间戳 → 北京时间字符串 */
function fmtTime(ms) {
  try {
    return new Date(ms).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
  } catch (e) {
    return String(ms);
  }
}
