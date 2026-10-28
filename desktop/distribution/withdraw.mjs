// Revoke a version with a NEW signed sequence; never replay an old manifest.
import { spawnSync } from 'node:child_process';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { privateKey, envelope, decode } from './sign.mjs';
const [version,channel] = process.argv.slice(2);
if(!/^\d+(?:\.\d+){1,3}$/.test(version || '') || !['dev','stable'].includes(channel)) throw Error('Usage: withdraw.mjs <version> <dev|stable>');
function ssh(...args) { const p=spawnSync('ssh',['hermes',...args],{encoding:'utf8'}); if(p.status) throw Error(p.stderr); return p.stdout; }
const pointer=JSON.parse(ssh('cat',`/opt/bilipai-distribution/data/channels/${channel}/current.json`));
if(!Number.isSafeInteger(pointer.sequence) || pointer.sequence <= 0) throw Error('Invalid current sequence');
const remote=`/opt/bilipai-distribution/data/channels/${channel}/${pointer.sequence}`;
const manifest=decode(ssh('cat',`${remote}/manifest.json`),'manifest');
const config=decode(ssh('cat',`${remote}/config.json`),'config');
const publication=JSON.parse(ssh('cat',`${remote}/publish.json`));
const now=Math.floor(Date.now()/1000), sequence=Math.max(now,pointer.sequence+1);
const header={sequence,issuedAt:now,expiresAt:now+7*86400};
const key=await privateKey(process.env.BILIPAI_SIGNING_KEY || path.join(os.homedir(),'.config/bilipai/signing/distribution-ed25519.pem'));
const withdrawn=[...new Set([...manifest.withdrawn,version])];
if(withdrawn.length>128) throw Error('Too many withdrawals; prepare a new channel manifest');
const out=await mkdtemp(path.join(process.cwd(),'withdrawal-'));
try {
await writeFile(path.join(out,'manifest.json'),envelope({...manifest,...header,withdrawn},key));
await writeFile(path.join(out,'config.json'),envelope({...config,...header},key));
await writeFile(path.join(out,'publish.json'),JSON.stringify({...publication,version:publication.version,sequence,files:[],catalog:publication.catalog.version === version ? {...publication.catalog,withdrawn:true} : publication.catalog,withdrawnVersion:version}));
const incoming=`/opt/bilipai-distribution/incoming/${channel}-${sequence}-withdrawal`;
ssh('mkdir','-p',incoming);
const copy=spawnSync('scp',['manifest.json','config.json','publish.json'].map(n=>path.join(out,n)).concat(`hermes:${incoming}/`),{encoding:'utf8'});
if(copy.status) throw Error(copy.stderr);
ssh('chmod','-R','a+rX',incoming);
console.log(ssh('sudo','-n','-u','bilipai-distribution','python3','/opt/bilipai-distribution/publish.py','/opt/bilipai-distribution/data',incoming));
console.log('Withdrawn '+version+'; currently running healthy versions are not forcibly downgraded.');

} finally { await rm(out,{recursive:true,force:true}); }
