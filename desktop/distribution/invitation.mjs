// Run on the server. Codes and the registry never enter the source repository.
import { randomBytes, createHash } from 'node:crypto';
import { readFile, writeFile, rename, mkdir } from 'node:fs/promises';
import path from 'node:path';
const [operation, label, channel = 'stable'] = process.argv.slice(2);
if (!['create','revoke'].includes(operation) || !/^[A-Za-z0-9_-]{1,40}$/.test(label || '') || !['dev','stable'].includes(channel)) {
  throw Error('Usage: invitation.mjs create|revoke <label> [dev|stable]');
}
const dir = path.join(process.env.BILIPAI_DISTRIBUTION_ROOT || '/opt/bilipai-distribution/data','private');
await mkdir(dir,{recursive:true,mode:0o700});
const file = path.join(dir,'invitations.json');
const lock = path.join(dir,'invitations.lock');
await mkdir(lock,{mode:0o700});
try {
  let registry;
  try { registry=JSON.parse(await readFile(file,'utf8')); } catch(e) { if(e.code !== 'ENOENT') throw e; registry={}; }
  if (operation === 'create') {
    if (Object.values(registry).some(i => i.label === label)) throw Error('Label already exists; use a new label');
    const code = randomBytes(24).toString('base64url');
    registry[createHash('sha256').update(code).digest('hex')]={label,enabled:true,channels:[channel]};
    await writeFile(file+'.new',JSON.stringify(registry)+'\n',{mode:0o600}); await rename(file+'.new',file);
    console.log(code);
  } else {
    const matches = Object.values(registry).filter(i => i.label === label);
    if(matches.length !== 1) throw Error('Invitation label not found or ambiguous');
    matches[0].enabled=false;
    await writeFile(file+'.new',JSON.stringify(registry)+'\n',{mode:0o600}); await rename(file+'.new',file);
    console.log('revoked '+label);
  }
} finally { const {rmdir}=await import('node:fs/promises'); await rmdir(lock); }
