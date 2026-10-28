import http from 'node:http';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile, stat, appendFile, realpath } from 'node:fs/promises';
import { createReadStream, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const digest = value => createHash('sha256').update(value).digest('hex');
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const page = body => `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>BiliPai 非官方 Windows 版</title><body><main><h1>BiliPai 非官方 Windows 版</h1>${body}<hr><p>登录信息只留在你的电脑。分发服务器不收集 B 站账号、Cookie 或 token，不做遥测。</p><p><a href="https://github.com/tonysuper666-creator/BiliPai-Windows/tree/desktop/integration-263-claude-main">公开源码</a> · <a href="https://github.com/tonysuper666-creator/BiliPai-Windows/blob/desktop/integration-263-claude-main/LICENSE">GPL-3.0 许可证</a></p></main></body></html>`;

export function createDistributionServer({ root, prefix = '/bilipai', now = () => Date.now() }) {
  root = realpathSync(root);
  if (prefix !== '' && (!/^\/[a-zA-Z0-9/-]+$/.test(prefix) || prefix.endsWith('/'))) throw Error('Invalid URL prefix');
  const sessions = new Map();
  const attempts = new Map();
  const readJson = async name => JSON.parse(await readFile(path.join(root, name), 'utf8'));
  async function current(channel) {
    const pointer = await readJson(`channels/${channel}/current.json`);
    if (!Number.isSafeInteger(pointer.sequence) || pointer.sequence <= 0) throw Error('Invalid channel pointer');
    return `channels/${channel}/${pointer.sequence}`;
  }
  async function catalog() {
    const releases = [];
    for (const channel of ['dev','stable']) {
      try { const directory = await current(channel); const publication = await readJson(`${directory}/publish.json`); releases.push(publication.catalog); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    return {releases};
  }
  async function invitation(req) {
    const token = /(?:^|;\s*)bilipai_invite=([0-9a-f]{64})(?:;|$)/.exec(req.headers.cookie || '')?.[1];
    const session = sessions.get(token);
    if (!session || session.until <= now()) { sessions.delete(token); return null; }
    const invites = await readJson('private/invitations.json');
    const invite = invites[session.hash];
    return invite?.enabled === true ? invite : null;
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'none'; img-src 'self'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    const reply = (code, body, type = 'text/plain; charset=utf-8') => { res.writeHead(code, {'Content-Type':type}); res.end(body); };
    try {
      const url = new URL(req.url, 'http://localhost');
      const pathname = url.pathname;
      if (req.method === 'POST' && pathname === '/unlock') {
        if (req.headers['content-type']?.split(';')[0] !== 'application/x-www-form-urlencoded') return reply(415, 'Unsupported form');
        for (const [key, value] of attempts) if (value.until <= now()) attempts.delete(key);
        if (attempts.size >= 2048) return reply(429, '请稍后再试');
        const address = req.socket.remoteAddress;
        const attempt = attempts.get(address) || {count:0, until:now()+600_000};
        attempts.set(address, attempt);
        if (++attempt.count > 30) return reply(429, '请稍后再试');
        const chunks = []; let size = 0;
        for await (const chunk of req) { size += chunk.length; if (size > 1024) return reply(413, '表单过大'); chunks.push(chunk); }
        const code = new URLSearchParams(Buffer.concat(chunks).toString('utf8')).get('code') || '';
        if (!/^[A-Za-z0-9_-]{24,128}$/.test(code)) return reply(403, '邀请码无效或已作废');
        const hash = digest(code);
        const invites = await readJson('private/invitations.json');
        const match = Object.keys(invites).find(key => key.length === hash.length && timingSafeEqual(Buffer.from(key), Buffer.from(hash)));
        if (!match || invites[match].enabled !== true) return reply(403, '邀请码无效或已作废');
        for (const [key, value] of sessions) if (value.until <= now()) sessions.delete(key);
        if (sessions.size >= 2048) return reply(503, '请稍后再试');
        const token = randomBytes(32).toString('hex');
        sessions.set(token, {hash, until:now()+86400_000});
        res.setHeader('Set-Cookie', `bilipai_invite=${token}; Path=${prefix}/; HttpOnly; Secure; SameSite=Strict; Max-Age=86400`);
        res.writeHead(303, {Location:prefix+'/'}); return res.end();
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') return reply(405, 'Method not allowed');
      if (pathname === '/healthz') return reply(200, 'ok');
      if (pathname === '/') {
        const invite = await invitation(req);
        if (!invite) return reply(200, page(`<p>仅供熟人测试。请输入单独发给你的邀请码。</p><form method="post" action="${prefix}/unlock"><label>邀请码 <input name="code" type="password" required autocomplete="off" maxlength="128"></label><button>查看下载</button></form>`), 'text/html; charset=utf-8');
        const listing = await catalog();
        const items = listing.releases.filter(r => !r.withdrawn && invite.channels?.includes(r.channel));
        const html = items.map(r => `<article><h2>${escape(r.version)} · ${escape(r.channel)}</h2><p>${escape(r.notes)}</p><p><a href="${prefix}/download/${encodeURIComponent(r.version)}/${encodeURIComponent(r.installer)}">下载安装包（${Math.ceil(r.size/1024/1024)} MB）</a></p></article>`).join('') || '<p>当前还没有可下载的版本。</p>';
        return reply(200, page(`${html}<h2>安装步骤</h2><ol><li>下载并双击安装包，按向导安装。无需另装 Java。</li><li>若 SmartScreen 提示“Windows 已保护你的电脑”，先点“更多信息”，核对文件来自本页，再点“仍要运行”。未出现此选项时请联系发给你链接的人，不要关闭系统防护。</li><li>从开始菜单打开 BiliPai Windows，登录你自己的 B 站账号。</li><li>以后更新在后台下载，空闲时切换；新版启动检查失败会保留上一版。</li></ol><p>卸载：Windows 设置 → 应用 → 已安装的应用 → BiliPai Windows → 卸载。</p>`), 'text/html; charset=utf-8');
      }
      let relative;
      let download;
      const metadataRoute = /^\/(updates|config)\/(dev|stable)\.json$/.exec(pathname);
      if (metadataRoute) relative = (await current(metadataRoute[2])) + (metadataRoute[1] === 'updates' ? '/manifest.json' : '/config.json');
      else if (/^\/artifacts\/[A-Za-z0-9_.+-]+\/BiliPai-Windows-[A-Za-z0-9_.+-]+\.zip(?:\.sha256)?$/.test(pathname)) relative = 'public'+pathname;
      else {
        const match = /^\/download\/([A-Za-z0-9_.+-]+)\/([A-Za-z0-9_.+ -]+\.(?:msi|exe))$/.exec(decodeURIComponent(pathname));
        if (!match) return reply(404, 'Not found');
        const invite = await invitation(req);
        if (!invite) return reply(403, '邀请码无效、已作废或会话已过期');
        const listing = await catalog();
        const release = listing.releases.find(r => !r.withdrawn && r.version === match[1] && r.installer === match[2] && invite.channels?.includes(r.channel));
        if (!release) return reply(404, 'Not found');
        relative = `installers/${match[1]}/${match[2]}`;
        download = {code:invite.label, time:new Date(now()).toISOString(), version:release.version};
      }
      const file = path.join(root, relative);
      const canonical = await realpath(file);
      if (!canonical.startsWith(root+path.sep) || canonical !== file) return reply(404, 'Not found');
      const info = await stat(file);
      if (!info.isFile()) return reply(404, 'Not found');
      if (download && req.method === 'GET') await appendFile(path.join(root, 'private/downloads.jsonl'), JSON.stringify(download)+'\n', {mode:0o600});
      res.writeHead(200, {'Content-Type':relative.endsWith('.json')?'application/json':'application/octet-stream', 'Content-Length':info.size});
      if (req.method === 'HEAD') return res.end();
      const stream = createReadStream(file);
      stream.on('error', () => res.destroy());
      res.on('close', () => stream.destroy());
      stream.pipe(res);
    } catch (error) {
      if (!res.headersSent) reply(error.code === 'ENOENT' ? 404 : 503, error.code === 'ENOENT' ? 'Not found' : '暂时不可用');
      else res.destroy();
    }
  });
  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = process.env.BILIPAI_DISTRIBUTION_ROOT || '/opt/bilipai-distribution/data';
  const server = createDistributionServer({root, prefix:process.env.BILIPAI_URL_PREFIX ?? '/bilipai'});
  server.listen(Number(process.env.PORT || 8140), '127.0.0.1', () => console.log('BiliPai distribution listening on loopback'));
}
