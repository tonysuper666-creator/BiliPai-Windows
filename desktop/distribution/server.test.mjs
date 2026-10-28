import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createDistributionServer } from './server.mjs';

test('invitation, download, revocation and privacy over real HTTP', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bilipai-distribution-'));
  const code = 'valid-friend-code-1234567890123456';
  const key = createHash('sha256').update(code).digest('hex');
  const invites = {[key]:{enabled:true, label:'friend-01', channels:['dev']}};
  for (const d of ['private', 'channels/dev/1', 'installers/0.2.427.201']) await mkdir(path.join(root,d),{recursive:true});
  await writeFile(path.join(root,'private/invitations.json'),JSON.stringify(invites));
  await writeFile(path.join(root,'channels/dev/1/publish.json'),JSON.stringify({catalog:{channel:'dev',version:'0.2.427.201',installer:'BiliPai-Windows-0.2.427.201.msi',size:4,notes:'<test>'}}));
  await writeFile(path.join(root,'channels/dev/current.json'),JSON.stringify({sequence:1}));
  await writeFile(path.join(root,'installers/0.2.427.201/BiliPai-Windows-0.2.427.201.msi'),'MSI!');
  await writeFile(path.join(root,'channels/dev/1/manifest.json'),'signed manifest');
  const server = createDistributionServer({root});
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = (url, options = {}) => fetch(base+url,{...options, redirect:'manual'});
  const unlock = code => request('/unlock',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({code})});
  try {
    assert.equal((await request('/')).status,200);
    assert.equal((await request('/download/0.2.427.201/BiliPai-Windows-0.2.427.201.msi')).status,403);
    assert.equal((await unlock('revoked-invalid-code-1234567890')).status,403);
    const accepted = await unlock(code);
    assert.equal(accepted.status,303);
    const cookie = accepted.headers.get('set-cookie').split(';')[0];
    const options = {headers:{cookie}};
    const page = await (await request('/',options)).text();
    assert.ok(page.includes('&lt;test&gt;'));
    assert.ok(page.includes('无需另装 Java'));
    const download = await request('/download/0.2.427.201/BiliPai-Windows-0.2.427.201.msi',options);
    assert.equal(download.status,200); assert.equal(await download.text(),'MSI!');
    const log = JSON.parse((await readFile(path.join(root,'private/downloads.jsonl'),'utf8')).trim());
    assert.deepEqual(Object.keys(log).sort(),['code','time','version']);
    assert.equal(log.code,'friend-01');
    assert.ok(!JSON.stringify(log).includes(code));
    assert.equal((await request('/private/invitations.json',options)).status,404);
    assert.equal((await request('/updates/dev.json?version=1&channel=dev&installationId=random')).status,200);
    invites[key].enabled = false;
    await writeFile(path.join(root,'private/invitations.json'),JSON.stringify(invites));
    assert.equal((await request('/download/0.2.427.201/BiliPai-Windows-0.2.427.201.msi',options)).status,403);
    assert.equal((await unlock(code)).status,403);
    console.log('valid invitation: HTTP 303; authorized installer: HTTP 200; revoked invitation/session: HTTP 403');
  } finally {
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    await rm(root,{recursive:true,force:true});
  }
});

test('dedicated hostname can serve invitation forms and sessions at its root', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(),'bilipai-root-'));
  const code = 'root-host-invitation-123456789';
  const hash = createHash('sha256').update(code).digest('hex');
  await mkdir(path.join(root,'private'));
  await writeFile(path.join(root,'private/invitations.json'),JSON.stringify({[hash]:{enabled:true,label:'root-dev',channels:['dev']}}));
  const server=createDistributionServer({root,prefix:''});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  try {
    assert.ok((await (await fetch(base+'/')).text()).includes('action="/unlock"'));
    const response=await fetch(base+'/unlock',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({code})});
    assert.equal(response.status,303);
    assert.equal(response.headers.get('location'),'/');
    assert.ok(response.headers.get('set-cookie').includes('Path=/;'));
  } finally {
    server.closeAllConnections(); await new Promise(resolve=>server.close(resolve));
    await rm(root,{recursive:true,force:true});
  }
});
