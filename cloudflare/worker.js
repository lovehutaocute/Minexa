// Minexa 累计独立用户统计 Worker
// 接口：
//   GET  /            返回完整前端统计页（服务端内联最新数据）
//   GET  /stats       -> { total: N }   累计独立用户数
//   POST /pulse       { deviceId }      设备上报（SHA-256 去重，新设备才累加）
//   POST /reset       { code }          清空统计；确认码读自 secret env.RESET_CODE（未配置则拒绝）
// 只读接口带 CORS 头（*）；/reset 仅同源（或 RESET_ALLOWED_ORIGINS 里列出的来源），并按 IP 限流。
//
// Secrets：
//   COUNT/绑定 KV -> env.COUNTS
//   RESET_CODE            清空确认码（不配则 /reset 直接 503）
//   RESET_ALLOWED_ORIGINS 允许跨域调用 /reset 的来源，逗号分隔
//   PULSE_TOKEN           可选；配了之后 /pulse 必须带同名 header，第三方无法伪造上报
const PULSE_MAX_PER_MINUTE = 20;
const PULSE_MAX_NEW_PER_DAY = 5;

const PAGE_TEMPLATE = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Minexa · 使用统计</title>
<style>
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
    background: radial-gradient(1200px 600px at 20% 10%, #312e81 0%, transparent 50%),
                radial-gradient(1000px 500px at 80% 90%, #1e3a8a 0%, transparent 50%),
                #0f172a;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
    color: #e2e8f0; padding: 24px; }
  .card { background: rgba(255,255,255,.05); border:1px solid rgba(255,255,255,.1);
    border-radius: 24px; padding: 44px 56px; text-align:center; backdrop-filter: blur(10px);
    box-shadow: 0 24px 70px rgba(0,0,0,.45); max-width: 92vw; width: 520px; }
  .badge { display:inline-block; background:#4f46e5; color:#fff; font-size:12px; letter-spacing:2px;
    padding:5px 14px; border-radius:999px; font-weight:600; }
  h1 { font-size:24px; margin:18px 0 6px; color:#fff; font-weight:700; }
  .sub { color:#94a3b8; font-size:14px; margin-bottom:26px; }
  .num { font-size:84px; font-weight:800; color:#fff; line-height:1; font-variant-numeric: tabular-nums; }
  .num b { color:#818cf8; }
  .label { color:#94a3b8; font-size:15px; margin-top:10px; }
  .meta { margin-top:28px; color:#64748b; font-size:12px; border-top:1px solid rgba(255,255,255,.08);
    padding-top:16px; }
  .pulse { display:inline-block; width:8px; height:8px; border-radius:50%; background:#22c55e;
    margin-right:7px; animation: blink 1.6s infinite; }
  @keyframes blink { 0%,100%{opacity:1} 50%{opacity:.25} }
  @media (max-width:480px) { .card { padding:32px 24px; } .num { font-size:64px; } }
</style>
</head>
<body>
  <div class="card">
    <span class="badge">MINESERVE MOBILE</span>
    <h1>累计使用人数</h1>
    <div class="sub">累计独立用户统计 · 数据实时同步</div>
    <div class="num"><b id="total">__TOTAL__</b></div>
    <div class="label"><span class="pulse"></span>位用户正在使用 Minexa 运行服务器</div>
    <div class="meta">更新于 __UPDATED__ · 数据由 Cloudflare Workers 统计</div>
    <div style="margin-top:16px;">
      <button onclick="resetData()"
        style="background:transparent;color:#f87171;border:1px solid rgba(248,113,113,.4);
        border-radius:8px;padding:6px 16px;font-size:12px;cursor:pointer;">清空数据</button>
    </div>
    <div id="resetMsg" style="margin-top:8px;font-size:12px;color:#94a3b8;"></div>
  </div>
  <script>
    function resetData() {
      if (!confirm("确认清空全部累计使用人数统计？此操作不可恢复！")) return;
      var code = prompt("请输入服务端配置的确认码：");
      if (!code) return;
      fetch("/reset", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: code })
      }).then(function (r) { return r.json(); }).then(function (d) {
        var msg = document.getElementById("resetMsg");
        if (d.ok) {
          msg.textContent = "已清空 " + (d.cleared || 0) + " 条设备记录";
          msg.style.color = "#22c55e";
          document.getElementById("total").textContent = "0";
        } else {
          msg.textContent = "清空失败：" + (d.error || "未知错误");
          msg.style.color = "#f87171";
        }
      }).catch(function () {
        var msg = document.getElementById("resetMsg");
        msg.textContent = "网络错误，清空失败";
        msg.style.color = "#f87171";
      });
    }
  </script>
</body>
</html>`;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const isReset = path === '/reset';

    // 只读统计接口允许任意来源；/reset 有副作用，收敛为「仅同源或同源 Origin」，
    // 不再返回 *，避免任意网页跨域触发清空。
    const origin = request.headers.get('Origin');
    // 独立部署的前端（如单独托管的 index.html）跨域调用：/reset 默认只放行同源，
    // 需要的额外来源写进 secret env.RESET_ALLOWED_ORIGINS（逗号分隔）。
    const allowedResetOrigins = (env.RESET_ALLOWED_ORIGINS || '')
      .split(',').map((s) => s.trim()).filter(Boolean);
    const corsFor = (allowAny) => {
      if (allowAny) return { 'Access-Control-Allow-Origin': '*' };
      // 无 Origin（curl / 服务端调用）不算同源请求，只在显式列出目的时才放行。
      if (origin === null) return { 'Vary': 'Origin' };
      if (origin === url.origin || allowedResetOrigins.includes(origin)) {
        return { 'Access-Control-Allow-Origin': origin, 'Vary': 'Origin' };
      }
      return { 'Vary': 'Origin' };
    };
    const base = {
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Cache-Control': 'no-store',
    };
    const readHeaders = { ...base, ...corsFor(true) };
    const resetHeaders = { ...base, ...corsFor(false) };
    const json = (obj, status = 200, reset = false) =>
      new Response(JSON.stringify(obj), {
        status,
        headers: { ...(reset ? resetHeaders : readHeaders), 'Content-Type': 'application/json' },
      });

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: isReset ? resetHeaders : readHeaders });
    }

    // 完整前端统计页（服务端内联最新数据，打开即有数字）
    if (path === '/') {
      const total = parseInt((await env.COUNTS.get('total')) || '0', 10);
      const updated = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
      const html = PAGE_TEMPLATE
        .replace('__TOTAL__', total.toLocaleString('zh-CN'))
        .replace('__UPDATED__', updated);
      return new Response(html, {
        status: 200,
        headers: { ...readHeaders, 'Content-Type': 'text/html; charset=utf-8' },
      });
    }

    // 累计独立用户数
    if (path === '/stats') {
      const total = parseInt((await env.COUNTS.get('total')) || '0', 10);
      return json({ total });
    }

    // 清空统计数据：确认码来自 Cloudflare Worker secret（env.RESET_CODE），不再硬编码，
    // 且 fail-closed —— 未配置 secret 时直接拒绝，绝不执行任何删除。
    if (isReset) {
      if (request.method !== 'POST') {
        return json({ ok: false, error: 'Method Not Allowed' }, 405, true);
      }
      const resetCode = env.RESET_CODE;
      if (!resetCode) {
        return json({ ok: false, error: 'reset disabled' }, 503, true);
      }
      // 按来源做配额：确认码是短字符串，枚举成本不高，所以**失败也要消耗配额**——
      // 否则一次 429 都拿不到，可以无限次试。
      const ip = clientIp(request);
      const rl = await consumeQuota(env.COUNTS, 'reset:' + ip, 5, 5 * 60_000);
      if (!rl.ok) return json({ ok: false, error: 'too many requests' }, 429, true);
      let body = {};
      try { body = await request.json(); } catch (e) {}
      const given = (body && typeof body.code === 'string') ? body.code : '';
      if (!timingSafeEqual(given, resetCode)) {
        return json({ ok: false, error: 'invalid code' }, 403, true);
      }
      let deleted = 0;
      let cursor;
      do {
        const page = await env.COUNTS.list({ prefix: 'seen:', cursor });
        for (const k of page.keys) {
          await env.COUNTS.delete(k.name);
          deleted++;
        }
        cursor = page.cursor;
      } while (cursor);
      await env.COUNTS.delete('total');
      return json({ ok: true, cleared: deleted, total: 0 }, 200, true);
    }

    // 设备上报（幂等：同一设备只计一次）
    //
    // 这个端点对公网开放，且**每一次新 deviceId 都会写 KV 并给 total +1**，
    // 所以它同时是「核心指标可信度」和「KV 写入配额」两件事的攻击面。
    // 防线：
    //  1. 配了 env.PULSE_TOKEN 时，App 必须带同名 header —— 第三方伪造不了；
    //  2. 按 IP 限制速率（每 IP 每分钟的写入次数有上限）；
    //  3. 每个 IP 每天能产生的「新设备」有上限，把 KV 键的增长钳在某个量级。
    // 想要严格精确（ CAS 去重 + 原子自增）得上 Durable Object，那超出免费档的复杂度，
    // 这里接受极小的计数误差，换取「刷不动」。
    if (path === '/pulse') {
      if (request.method !== 'POST') {
        return json({ ok: false, error: 'Method Not Allowed' }, 405);
      }
      const pulseToken = env.PULSE_TOKEN;
      if (pulseToken) {
        const given = request.headers.get('x-pulse-token') || '';
        // 与 RESET 一样也计入限流：口令在泄漏前应尽快暴露暴力尝试。
        if (!timingSafeEqual(given, pulseToken)) {
          return json({ ok: false, error: 'forbidden' }, 403);
        }
      }

      const ip = clientIp(request);
      const minute = await consumeQuota(env.COUNTS, 'rl:pulse:' + ip, PULSE_MAX_PER_MINUTE, 60_000);
      if (!minute.ok) return json({ ok: false, error: 'too many requests' }, 429);

      let raw = '';
      try {
        const body = await request.json();
        raw = (body && body.deviceId ? body.deviceId : '').toString().trim();
      } catch (e) {
        raw = '';
      }
      if (!raw || raw.length > 128) {
        return json({ ok: false, error: 'missing or invalid deviceId' }, 400);
      }

      const hash = await sha256(raw);
      const seenKey = 'seen:' + hash;

      // 已见过该设备：直接返回当前总数，不重复累加
      const total0 = parseInt((await env.COUNTS.get('total')) || '0', 10);
      if (await env.COUNTS.get(seenKey)) {
        return json({ ok: true, newUser: false, total: total0 });
      }

      // 新设备：先占配额再累加。同一 IP 每天能贡献的新设备有限，
      // 于是「循环随机 deviceId」最多每天推高这个上限，而不是 unbounded。
      const day = await consumeQuota(env.COUNTS, 'rl:new:' + ip, PULSE_MAX_NEW_PER_DAY, 24 * 3600_000);
      if (!day.ok) {
        return json({ ok: false, error: 'too many requests' }, 429);
      }

      // 先落 seen 再自增：即便自增丢了，也只是少算一个用户，不会重复计数。
      await env.COUNTS.put(seenKey, '1');
      const total = total0 + 1;
      await env.COUNTS.put('total', total.toString());
      return json({ ok: true, newUser: true, total });
    }

    return new Response('Not Found', { status: 404, headers: base });
  },
};

async function sha256(text) {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/** Cloudflare 把真实来源 IP 放在这个头里（取不到时退回 unknown，只影响限流粒度）。 */
function clientIp(request) {
  return (request.headers.get('CF-Connecting-IP') || 'unknown').trim();
}

/**
 * 近似定长时间比较：不因「第几个字符先不同」而泄露信息。
 * 两个入参都是短口令，网络噪声下的实际可利用性有限，但代价几乎为零。
 */
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
 * 简易配额：KV 计数 + TTL 滑动窗口。
 *
 * 明确承认它的局限——KV 是最终一致的，并发请求读到同一个旧值时可能同时通过，
 * 上界不是硬保证（要硬保证得上 Durable Object 或 Rate Limiting binding）。
 * 但比「完全不限」好得多：把刷量从 unbounded 压到 每窗口 × limit + 一点并发溢出。
 */
async function consumeQuota(kv, key, limit, windowMs) {
  const now = Date.now();
  const raw = await kv.get(key, 'json');
  let count = 0;
  if (raw && typeof raw.count === 'number' && raw.resetAt > now) count = raw.count;
  if (count >= limit) return { ok: false };
  await kv.put(key, JSON.stringify({ count: count + 1, resetAt: now + windowMs }), {
    expirationTtl: Math.ceil(windowMs / 1000),
  });
  return { ok: true };
}
