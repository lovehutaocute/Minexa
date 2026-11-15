// worker.js 离线自测：用假的 env.DB / env.RATE 跑一遍所有接口。
// 不需要联网、不需要 Cloudflare 账号。
//
// 运行：node test-worker.mjs
//
// 用途：部署前确认逻辑没写错。部署后仍要用真实请求再测一次（见 README）。

import worker from './worker.js';

const REPORT_KEY = 'reportkey-0123456789abcdef';
const VIEW_PASSWORD = 'viewpass-0123456789abcdef';
const COOKIE = 'minexa_crash_view';

// ---- 假的 D1：实现本 worker 实际用到的 SQL -------------------------------
function makeFakeDB() {
  const rows = [];
  let nextId = 1;

  // 极简 SQL 解析：只认本 worker 会发的几种查询
  return {
    rows,
    prepare(sql) {
      const stmt = {
        params: [],
        bind(...p) {
          stmt.params = p;
          return stmt;
        },
        async first() {
          if (/SELECT COUNT\(\*\) AS n FROM crashes/.test(sql)) {
            const f = parseWhere(sql, stmt.params);
            return { n: rows.filter(f).length };
          }
          if (/SELECT id FROM crashes WHERE fingerprint = \? AND created_at > \?/.test(sql)) {
            const [fp, since] = stmt.params;
            const hit = rows.find((r) => r.fingerprint === fp && r.created_at > since);
            return hit ? { id: hit.id } : null;
          }
          return null;
        },
        async run() {
          const p = stmt.params;
          // 去重累加：UPDATE ... SET occurrences = occurrences + 1 ... WHERE id = ?
          if (/^UPDATE crashes\s+SET occurrences/.test(sql.trim())) {
            const id = p[p.length - 1];
            const row = rows.find((r) => r.id === id);
            if (row) {
              row.occurrences = (row.occurrences || 1) + 1;
              row.last_at = p[0];
              row.stack = p[1];
              row.occurred_at = p[2];
              row.model = p[3];
              row.android = p[4];
              row.abi = p[5];
              row.thread = p[6];
            }
            return { success: true };
          }
          rows.push({
            id: nextId++,
            fingerprint: p[0],
            version: p[1],
            versionCode: p[2],
            model: p[3],
            android: p[4],
            abi: p[5],
            thread: p[6],
            stack: p[7],
            occurred_at: p[8],
            created_at: p[9],
            occurrences: 1,
            last_at: p[10] !== undefined ? p[10] : p[9],
          });
          return { success: true };
        },
        async all() {
          // 分页查询
          if (/FROM crashes.*ORDER BY created_at DESC LIMIT \? OFFSET \?/.test(sql) ||
              /FROM crashes.*ORDER BY created_at DESC LIMIT \?$/.test(sql)) {
            const f = parseWhere(sql, stmt.params);
            const matched = rows.filter(f).sort((a, b) => b.created_at - a.created_at);
            const nums = stmt.params.filter((x) => typeof x === 'number');
            const limit = nums[nums.length - 2] !== undefined ? nums[nums.length - 2] : 50;
            const offset = nums.length >= 2 ? nums[nums.length - 1] : 0;
            // LIMIT 后面两个参数是 limit,offset
            const tail = stmt.params.slice(-2);
            const lim = tail[0];
            const off = tail[1] || 0;
            return { results: matched.slice(off, off + lim) };
          }
          // 出现次数统计已改为表内字段，这里不再需要分组查询
          return { results: [] };
          // 筛选下拉框候选值
          if (/SELECT DISTINCT version FROM crashes/.test(sql)) {
            return { results: [...new Set(rows.map((r) => r.version))].map((v) => ({ version: v })) };
          }
          if (/SELECT DISTINCT model FROM crashes/.test(sql)) {
            return { results: [...new Set(rows.map((r) => r.model))].map((v) => ({ model: v })) };
          }
          if (/SELECT DISTINCT android FROM crashes/.test(sql)) {
            return { results: [...new Set(rows.map((r) => r.android))].map((v) => ({ android: v })) };
          }
          return { results: [] };
        },
      };
      return stmt;
    },
  };
}

/**
 * 把 WHERE 子句还原成过滤函数。参数顺序：先 WHERE 里的，再 LIMIT/OFFSET。
 * 这里只处理本 worker 会用到的四种条件。
 */
function parseWhere(sql, params) {
  const m = sql.match(/WHERE (.+?)(?: ORDER BY|$)/);
  if (!m) return () => true;
  const clause = m[1];
  const conds = [];
  let i = 0;
  if (/stack LIKE \?/.test(clause)) conds.push({ type: 'stack', val: params[i++] });
  if (/\bversion = \?/.test(clause)) conds.push({ type: 'version', val: params[i++] });
  if (/\bmodel = \?/.test(clause)) conds.push({ type: 'model', val: params[i++] });
  if (/\bandroid = \?/.test(clause)) conds.push({ type: 'android', val: params[i++] });

  return (r) =>
    conds.every((c) => {
      if (c.type === 'stack') {
        const needle = String(c.val).replace(/^%|%$/g, '');
        return String(r.stack).includes(needle);
      }
      return r[c.type] === c.val;
    });
}

function makeFakeKV() {
  const map = new Map();
  return {
    map,
    async get(k, type) {
      const v = map.get(k);
      if (!v) return null;
      return type === 'json' ? JSON.parse(v) : v;
    },
    async put(k, v) {
      map.set(k, v);
    },
  };
}

function makeEnv({ reportKey = true, viewPass = true, withDB = true, withRate = true } = {}) {
  return {
    REPORT_KEY: reportKey ? REPORT_KEY : undefined,
    VIEW_PASSWORD: viewPass ? VIEW_PASSWORD : undefined,
    DB: withDB ? makeFakeDB() : undefined,
    RATE: withRate ? makeFakeKV() : undefined,
  };
}

function req(path, opts = {}) {
  return new Request('https://example.com' + path, {
    method: opts.method || 'GET',
    headers: opts.headers || {},
    body: opts.body,
    redirect: 'manual',
  });
}

const reportBody = (over = {}) =>
  JSON.stringify({
    version: '1.5.1',
    versionCode: 62,
    model: 'AGS6-W00',
    android: '12',
    abi: 'arm64-v8a',
    thread: 'main',
    stack: 'java.lang.IllegalStateException: boom\n  at com.x.A.f(A.kt:1)\n  at com.x.B.g(B.kt:2)',
    occurredAt: Date.now(),
    ...over,
  });

let pass = 0;
let fail = 0;

function check(name, cond, detail) {
  if (cond) {
    pass++;
    console.log('  PASS ' + name);
  } else {
    fail++;
    console.log('  FAIL ' + name + (detail !== undefined ? '  → ' + JSON.stringify(detail) : ''));
  }
}

async function call(env, path, opts) {
  const res = await worker.fetch(req(path, opts), env, {});
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch (e) {
    body = text;
  }
  return { status: res.status, body, headers: res.headers };
}

const reportHeaders = { 'X-Key': REPORT_KEY, 'Content-Type': 'application/json' };
const cookieHeaders = { Cookie: `${COOKIE}=${encodeURIComponent(VIEW_PASSWORD)}` };

// ===========================================================================
console.log('\n[1] /health 不需要密钥');
{
  const env = makeEnv({ reportKey: false, viewPass: false });
  const r = await call(env, '/health');
  check('未配置任何密钥时 /health 仍可用', r.status === 200 && r.body.ok === true, r);
}

// ===========================================================================
console.log('\n[2] /report 鉴权与限流');
{
  const env = makeEnv();
  const noKey = await call(env, '/report', { method: 'POST', body: reportBody(), headers: { 'Content-Type': 'application/json' } });
  check('不带密钥 → 403', noKey.status === 403, noKey.status);

  const badKey = await call(env, '/report', { method: 'POST', body: reportBody(), headers: { 'X-Key': 'wrong', 'Content-Type': 'application/json' } });
  check('错误密钥 → 403', badKey.status === 403, badKey.status);

  const notPost = await call(env, '/report', { headers: reportHeaders });
  check('GET /report → 405', notPost.status === 405, notPost.status);

  const notConfigured = await call(makeEnv({ reportKey: false }), '/report', {
    method: 'POST', body: reportBody(), headers: reportHeaders,
  });
  check('服务端未配密钥 → 503（fail-closed）', notConfigured.status === 503, notConfigured.status);
}

// ===========================================================================
console.log('\n[3] 上报入库 + 去重');
{
  const env = makeEnv();
  const first = await call(env, '/report', { method: 'POST', body: reportBody(), headers: reportHeaders });
  check('首次上报 → duplicate:false', first.body.ok === true && first.body.duplicate === false, first.body);
  check('数据库 1 条', env.DB.rows.length === 1, env.DB.rows.length);

  const again = await call(env, '/report', { method: 'POST', body: reportBody(), headers: reportHeaders });
  check('同样内容 → duplicate:true', again.body.duplicate === true, again.body);
  check('数据库仍 1 条（去重）', env.DB.rows.length === 1, env.DB.rows.length);

  const addr = await call(env, '/report', {
    method: 'POST', headers: reportHeaders,
    body: reportBody({ stack: 'java.lang.IllegalStateException: boom\n  at com.x.A.f(A.kt:1, 0xdeadbeef)\n  at com.x.B.g(B.kt:2)' }),
  });
  check('仅内存地址不同 → 仍重复', addr.body.duplicate === true, addr.body);

  const lineNo = await call(env, '/report', {
    method: 'POST', headers: reportHeaders,
    body: reportBody({ stack: 'java.lang.IllegalStateException: boom\n  at com.x.A.f(A.kt:99)\n  at com.x.B.g(B.kt:120)' }),
  });
  check('仅行号不同 → 仍重复', lineNo.body.duplicate === true, lineNo.body);

  const msg = await call(env, '/report', {
    method: 'POST', headers: reportHeaders,
    body: reportBody({ stack: 'java.lang.IllegalStateException: /sdcard/我的存档/world.dat 打不开\n  at com.x.A.f(A.kt:1)\n  at com.x.B.g(B.kt:2)' }),
  });
  check('消息带用户数据 → 仍重复', msg.body.duplicate === true, msg.body);

  const diff = await call(env, '/report', {
    method: 'POST', headers: reportHeaders,
    body: reportBody({ stack: 'java.lang.OutOfMemoryError\n  at com.y.Z.h(Z.kt:9)' }),
  });
  check('不同堆栈 → 新记录', diff.body.duplicate === false, diff.body);
  check('数据库 2 条', env.DB.rows.length === 2, env.DB.rows.length);
}

// ===========================================================================
console.log('\n[3b] 不能误合并（反向保护）');
{
  const env = makeEnv();
  const a = 'java.lang.NullPointerException\n  at com.m.A.f(A.kt:10)\n  at com.m.B.g(B.kt:20)';
  const b = 'java.lang.NullPointerException\n  at com.m.A.f(A.kt:10)\n  at com.m.C.h(C.kt:30)';
  await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody({ stack: a }) });
  const r = await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody({ stack: b }) });
  check('同类型但调用路径不同 → 两条', r.body.duplicate === false, r.body);
  check('数据库 2 条（没有误合并）', env.DB.rows.length === 2, env.DB.rows.length);
}

// ===========================================================================
console.log('\n[3c] 字段校验');
{
  const env = makeEnv();
  const noStack = await call(env, '/report', { method: 'POST', headers: reportHeaders, body: JSON.stringify({ version: 'x' }) });
  check('缺 stack → 400', noStack.status === 400, noStack.status);

  const badJson = await call(env, '/report', { method: 'POST', headers: reportHeaders, body: '{not json' });
  check('非法 JSON → 400', badJson.status === 400, badJson.status);

  const huge = await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody({ stack: 'x'.repeat(100000) }) });
  check('超长堆栈入库但不炸', huge.status === 200, huge.status);
  check('堆栈被截断到 16KB', env.DB.rows[0].stack.length <= 16 * 1024, env.DB.rows[0].stack.length);
}

// ===========================================================================
console.log('\n[4] 登录');
{
  const env = makeEnv();

  const getLogin = await call(env, '/login');
  check('GET /login 显示表单', getLogin.status === 200 && String(getLogin.body).includes('输入查看密码'), getLogin.status);

  const wrong = await call(env, '/login?key=wrongpass', { method: 'POST' });
  check('密码错 → 403', wrong.status === 403, wrong.status);
  check('错误页给出提示', String(wrong.body).includes('密码不对'));

  const good = await call(env, '/login?key=' + encodeURIComponent(VIEW_PASSWORD), { method: 'POST' });
  check('密码对 → 302 跳转', good.status === 302, good.status);
  const setCookie = good.headers.get('Set-Cookie') || '';
  check('写入了 Cookie', setCookie.includes(COOKIE), setCookie);
  check('Cookie 是 HttpOnly', setCookie.includes('HttpOnly'), setCookie);
  check('Cookie 是 Secure', setCookie.includes('Secure'), setCookie);
  check('Cookie SameSite=Lax', setCookie.includes('SameSite=Lax'), setCookie);
  check('Cookie 有 30 天有效期', setCookie.includes('Max-Age='), setCookie);

  const lo = await call(env, '/logout');
  check('登出 → 302 且清除 Cookie', lo.status === 302 && (lo.headers.get('Set-Cookie') || '').includes('Max-Age=0'));
}

// ===========================================================================
console.log('\n[5] 未登录访问被挡住');
{
  const env = makeEnv();
  await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody() });

  const list = await call(env, '/list');
  check('/list 无 Cookie → 302 去登录页', list.status === 302, list.status);
  check('跳转目标含 /login', (list.headers.get('Location') || '').includes('/login'), list.headers.get('Location'));

  const api = await call(env, '/api/list');
  check('/api/list 无 Cookie → 401', api.status === 401, api.status);

  const badCookie = await call(env, '/list', { headers: { Cookie: `${COOKIE}=wrongvalue` } });
  check('错误 Cookie → 仍然 302', badCookie.status === 302, badCookie.status);

  const goodCookie = await call(env, '/list', { headers: cookieHeaders });
  check('正确 Cookie → 200', goodCookie.status === 200, goodCookie.status);

  const query = await call(env, '/api/list?key=' + encodeURIComponent(VIEW_PASSWORD));
  check('查询参数也能授权（命令行用）', query.status === 200, query.status);

  // 上报密钥不能用来查看（若配了 VIEW_PASSWORD）
  const reportKeyAsView = await call(env, '/list', { headers: { Cookie: `${COOKIE}=${encodeURIComponent(REPORT_KEY)}` } });
  check('上报密钥不能当查看密码用', reportKeyAsView.status === 302, reportKeyAsView.status);
}

// ===========================================================================
console.log('\n[5b] 没配 VIEW_PASSWORD 时回退用 REPORT_KEY');
{
  const env = makeEnv({ viewPass: false });
  await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody() });
  const r = await call(env, '/list', { headers: { Cookie: `${COOKIE}=${encodeURIComponent(REPORT_KEY)}` } });
  check('回退生效 → 200', r.status === 200, r.status);
}

// ===========================================================================
console.log('\n[6] 列表分页与筛选');
{
  const env = makeEnv();
  // 造 12 条不同堆栈的记录
  for (let i = 0; i < 12; i++) {
    await call(env, '/report', {
      method: 'POST', headers: reportHeaders,
      body: reportBody({
        version: i < 6 ? '1.5.0' : '1.5.1',
        versionCode: i < 6 ? 61 : 62,
        model: i % 2 === 0 ? 'AGS6-W00' : 'V2520A',
        android: i % 3 === 0 ? '12' : '13',
        stack: `java.lang.Error${i}\n  at com.x.M${i}.f(M.kt:1)`,
      }),
    });
  }
  check('共 12 条', env.DB.rows.length === 12, env.DB.rows.length);

  const all = await call(env, '/api/list', { headers: cookieHeaders });
  check('默认返回 12 条', all.body.crashes.length === 12, all.body.crashes.length);
  check('total 字段正确', all.body.total === 12, all.body.total);

  const p1 = await call(env, '/api/list?pageSize=5&page=1', { headers: cookieHeaders });
  check('pageSize=5 第 1 页 → 5 条', p1.body.crashes.length === 5, p1.body.crashes.length);
  const p3 = await call(env, '/api/list?pageSize=5&page=3', { headers: cookieHeaders });
  check('第 3 页 → 2 条', p3.body.crashes.length === 2, p3.body.crashes.length);

  const byVer = await call(env, '/api/list?version=1.5.0', { headers: cookieHeaders });
  check('按版本筛选 → 6 条', byVer.body.crashes.length === 6, byVer.body.crashes.length);
  check('筛选后 total=6', byVer.body.total === 6, byVer.body.total);

  const byModel = await call(env, '/api/list?model=AGS6-W00', { headers: cookieHeaders });
  check('按机型筛选 → 6 条', byModel.body.crashes.length === 6, byModel.body.crashes.length);

  const byAndroid = await call(env, '/api/list?android=12', { headers: cookieHeaders });
  check('按 Android 版本筛选 → 4 条', byAndroid.body.crashes.length === 4, byAndroid.body.crashes.length);

  const bySearch = await call(env, '/api/list?q=Error3', { headers: cookieHeaders });
  check('搜索关键词 → 1 条', bySearch.body.crashes.length === 1, bySearch.body.crashes.length);

  const combo = await call(env, '/api/list?version=1.5.1&model=AGS6-W00', { headers: cookieHeaders });
  check('组合筛选生效', combo.body.crashes.length === 3, combo.body.crashes.length);

  const noHit = await call(env, '/api/list?q=完全不存在的字符串', { headers: cookieHeaders });
  check('无结果 → 0 条且不报错', noHit.body.crashes.length === 0 && noHit.body.total === 0, noHit.body);
}

// ===========================================================================
console.log('\n[7] 出现次数统计（去重时累加，不是丢弃）');
{
  const env = makeEnv();
  const stack = 'java.lang.Repeat\n  at com.x.R.f(R.kt:1)';
  // 同样堆栈连发 3 次：应该只留 1 条记录，但 occurrences 应该是 3
  await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody({ stack }) });
  await new Promise((r) => setTimeout(r, 5));
  await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody({ stack }) });
  await new Promise((r) => setTimeout(r, 5));
  await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody({ stack }) });

  check('去重后只有 1 条记录', env.DB.rows.length === 1, env.DB.rows.length);
  check('数据库里 occurrences = 3', env.DB.rows[0].occurrences === 3, env.DB.rows[0].occurrences);

  const r = await call(env, '/api/list', { headers: cookieHeaders });
  const row = r.body.crashes[0];
  check('接口返回 occurrences = 3', row && row.occurrences === 3, row && row.occurrences);
  check('有 last_at 字段', row && typeof row.last_at === 'number', row && row.last_at);
  check('last_at 不早于 occurred_at', row.last_at >= row.occurred_at, { last: row.last_at, occ: row.occurred_at });

  // 页面应该显示 ×3
  const page = await call(env, '/list', { headers: cookieHeaders });
  check('页面显示 ×3 标记', String(page.body).includes('×3'), '没看到次数标记');
}

// ===========================================================================
console.log('\n[8] 页面渲染与 XSS');
{
  const env = makeEnv();
  await call(env, '/report', {
    method: 'POST', headers: reportHeaders,
    body: reportBody({ stack: 'java.lang.X\n  <script>alert(1)</script>\n  <img src=x onerror=alert(2)>' }),
  });

  const page = await call(env, '/list', { headers: cookieHeaders });
  check('/list 返回 200', page.status === 200, page.status);
  check('页面是 HTML', String(page.body).includes('崩溃记录'));
  check('<script> 被转义', !String(page.body).includes('<script>alert(1)</script>'), 'XSS 未转义');
  check('&lt;script&gt; 出现', String(page.body).includes('&lt;script&gt;'), '没看到转义后的内容');
  check('onerror 属性也被转义', !String(page.body).includes('<img src=x onerror='), 'XSS 未转义');
  check('页面有复制按钮', String(page.body).includes('复制堆栈'));
  check('页面有筛选下拉框', String(page.body).includes('<select'));
  check('响应带 noindex', (page.headers.get('X-Robots-Tag') || '').includes('noindex'));
  check('响应带 X-Frame-Options', page.headers.get('X-Frame-Options') === 'DENY');
}

// ===========================================================================
console.log('\n[9] 登录跳转不能被利用（open redirect）');
{
  const env = makeEnv();
  const evil = await call(env, '/login?key=' + encodeURIComponent(VIEW_PASSWORD) + '&next=' + encodeURIComponent('https://evil.com/x'), { method: 'POST' });
  check('外部地址被忽略 → 回 /list', (evil.headers.get('Location') || '') === '/list', evil.headers.get('Location'));

  const proto = await call(env, '/login?key=' + encodeURIComponent(VIEW_PASSWORD) + '&next=' + encodeURIComponent('//evil.com'), { method: 'POST' });
  check('协议相对地址也被拦', (proto.headers.get('Location') || '') === '/list', proto.headers.get('Location'));

  const normal = await call(env, '/login?key=' + encodeURIComponent(VIEW_PASSWORD) + '&next=' + encodeURIComponent('/list?q=abc'), { method: 'POST' });
  check('正常的站内路径放行', (normal.headers.get('Location') || '') === '/list?q=abc', normal.headers.get('Location'));
}

// ===========================================================================
console.log('\n[10] 限流');
{
  const env = makeEnv();
  let blocked = false;
  for (let i = 0; i < 30; i++) {
    const r = await call(env, '/report', {
      method: 'POST',
      headers: { ...reportHeaders, 'CF-Connecting-IP': '1.2.3.4' },
      body: reportBody({ stack: 'java.lang.Run\n  at x.y(X.kt:' + i + ')' }),
    });
    if (r.status === 429) { blocked = true; break; }
  }
  check('上报超过每分钟上限 → 429', blocked, '未触发限流');
}
{
  const env = makeEnv();
  let blocked = false;
  for (let i = 0; i < 15; i++) {
    const r = await call(env, '/login?key=wrong' + i, {
      method: 'POST',
      headers: { 'CF-Connecting-IP': '9.9.9.9' },
    });
    if (r.status === 429) { blocked = true; break; }
  }
  check('登录失败太多次 → 429', blocked, '未触发登录限流');
}
{
  const env = makeEnv({ withRate: false });
  const r = await call(env, '/report', { method: 'POST', headers: reportHeaders, body: reportBody() });
  check('没绑定 KV 时限流跳过、上报照常', r.status === 200, r.status);
}

// ===========================================================================
console.log('\n[11] 杂项');
{
  const env = makeEnv();
  const missing = await call(env, '/nope');
  check('未知路径 → 404', missing.status === 404, missing.status);

  const opt = await worker.fetch(req('/report', { method: 'OPTIONS' }), env, {});
  check('OPTIONS → 204', opt.status === 204, opt.status);

  const noDB = await call(makeEnv({ withDB: false }), '/report', { method: 'POST', headers: reportHeaders, body: reportBody() });
  check('未绑定 DB → 503', noDB.status === 503, noDB.status);
}

// ===========================================================================
console.log('\n─────────────────────────────');
console.log(`通过 ${pass} 项，失败 ${fail} 项`);
console.log('─────────────────────────────');
if (fail > 0) {
  console.log('\n有失败项，先修好再部署。');
  process.exit(1);
} else {
  console.log('\n全部通过。可以部署了。');
}
