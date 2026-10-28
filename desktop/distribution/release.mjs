// Signing/upload step. Only exact packages with existing release-gate evidence
// are admitted. Windows build/Sandbox orchestration is deliberately not faked.
import { readFile, writeFile, mkdir, copyFile, stat } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { privateKey, envelope, archiveSignature, decode } from './sign.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here,'../..');
const [version, channel, mode = 'prepare'] = process.argv.slice(2);
if (!/^\d+(?:\.\d+){1,3}$/.test(version || '') || !['dev','stable'].includes(channel) || !['prepare','publish'].includes(mode))
  throw Error('Usage: release.sh <version> <dev|stable> [prepare|publish]; requires BILIPAI_ARTIFACT_DIR and exact release-gate.json');
const base = process.env.BILIPAI_DISTRIBUTION_BASE || 'https://learn.tonysuper666.com/bilipai';
if (!/^https:\/\/[A-Za-z0-9.-]+(?:\/[A-Za-z0-9_-]+)*$/.test(base)) throw Error('Invalid HTTPS distribution base');
const run = (command,args,options = {}) => {
  const result = spawnSync(command,args,{cwd:repo,encoding:'utf8',...options});
  if(result.error || result.status !== 0) throw Error(`${command} failed (${result.status}): ${result.stderr || result.error}`);
  return result.stdout.trim();
};
const sha = run('git',['rev-parse','HEAD']);
if(run('git',['status','--porcelain','--untracked-files=all'])) throw Error('Commit the exact source before signing');
const directory = process.env.BILIPAI_ARTIFACT_DIR;
if(!directory) throw Error('BILIPAI_ARTIFACT_DIR is required: Windows packaging/Sandbox gate has not yet been automated');
const gate = JSON.parse((await readFile(path.join(directory,'release-gate.json'),'utf8')).replace(/^\uFEFF/,''));
const gates = ['kotlinUnitTests','pythonSourceContractTests','guestNetworkBackendSmoke','packagedNativePlayerSmoke','packagedUpdaterSmoke','packagedNativeDownloadMuxSmoke'];
if(gate.passed !== true || gate.windowsSourceCommit !== sha || gate.windowsVersion !== version || gates.some(g => gate[g] !== 'passed'))
  throw Error('Release gate does not prove this exact source/version passed all existing gates');
if(channel === 'stable') {
  // Human sign-off file stays on the Mac. Stable cannot be enabled by the server.
  const approvalFile = process.env.BILIPAI_STABLE_APPROVAL;
  if(!approvalFile) throw Error('Stable blocked: TON-1009 acceptance and prior dev use by 李政 are required');
  const approval = JSON.parse(await readFile(approvalFile,'utf8'));
  const issue = JSON.parse(run('multica',['issue','get','TON-1009','--output','json']));
  if(issue.status !== 'done' || approval.devUsedBy !== '李政' || !/^\d+(?:\.\d+){1,3}$/.test(approval.devVersion || '') || !approval.evidence)
    throw Error('Stable prerequisites are not proven');
}
const zipName = `BiliPai-Windows-${version}-x64.zip`;
const installerName = process.env.BILIPAI_INSTALLER_NAME || `BiliPai-Windows-${version}.msi`;
if(!/^[A-Za-z0-9_.+ -]+\.(msi|exe)$/.test(installerName)) throw Error('Invalid installer filename');
async function digest(file) { const hash=createHash('sha256'); for await (const chunk of createReadStream(file)) hash.update(chunk); return hash.digest('hex'); }
const zipPath = path.join(directory,zipName), installerPath = path.join(directory,installerName);
const zipHash = await digest(zipPath);
if(zipHash !== gate.portableZipSha256) throw Error('ZIP differs from the tested release gate');
// Inspect the actual packaged resource, not a loose source-side config.
const packaged = JSON.parse(run(process.env.PYTHON_EXECUTABLE || 'python3',['-c',
  'import zipfile,io,json,sys\nz=zipfile.ZipFile(sys.argv[1]); found=[]\nfor n in z.namelist():\n if n.endswith(".jar"):\n  j=zipfile.ZipFile(io.BytesIO(z.read(n)))\n  if "windows-update.json" in j.namelist(): found.append(json.loads(j.read("windows-update.json")))\nassert len(found)==1, "Expected one packaged update configuration"\nprint(json.dumps(found[0]))', zipPath]));
if(packaged.version !== version || packaged.channel !== channel || packaged.source !== 'signed' || packaged.distributionBase !== base)
  throw Error('Packaged signed source/channel/version/base do not match publication');
const notes = await readFile(path.join(directory,'release-notes.txt'),'utf8');
if(!notes.trim()) throw Error('User-readable release notes required');
const key = await privateKey(process.env.BILIPAI_SIGNING_KEY || path.join(os.homedir(),'.config/bilipai/signing/distribution-ed25519.pem'));
const now = Math.floor(Date.now()/1000);
const sequence = Number(process.env.BILIPAI_SEQUENCE || now);
if(!Number.isSafeInteger(sequence) || sequence <= 0) throw Error('Invalid sequence');
const rolloutPercent = Number(process.env.BILIPAI_ROLLOUT || (channel === 'dev' ? 100 : 10));
if(!Number.isInteger(rolloutPercent) || rolloutPercent < 0 || rolloutPercent > 100) throw Error('Invalid rollout');
const output = path.resolve(process.env.BILIPAI_RELEASE_OUTPUT || path.join(repo,'../release-output'),`${channel}-${sequence}`);
await mkdir(output,{recursive:true});
const header = {schema:1,channel,sequence,issuedAt:now,expiresAt:now+7*86400};
const release = {version,releaseId:sequence,assetId:sequence,assetName:zipName,size:(await stat(zipPath)).size,
  downloadUrl:`${base}/artifacts/${version}/${zipName}`,sha256:zipHash,archiveSignature:archiveSignature(zipHash,key)};
const manifest = {...header,kind:'manifest',rolloutPercent,withdrawn:[],release};
const policyFile = process.env.BILIPAI_REMOTE_POLICY;
const policy = policyFile ? JSON.parse(await readFile(policyFile,'utf8')) : {automaticUpdatesEnabled:true,checkIntervalHours:6,announcement:''};
if(Object.keys(policy).some(k=>!['automaticUpdatesEnabled','checkIntervalHours','announcement'].includes(k)) ||
  typeof policy.automaticUpdatesEnabled !== 'boolean' || !Number.isInteger(policy.checkIntervalHours) ||
  policy.checkIntervalHours < 1 || policy.checkIntervalHours > 24 || typeof policy.announcement !== 'string' || policy.announcement.length > 1000)
  throw Error('Invalid declarative remote policy');
for(const [kind,value] of [['manifest',manifest],['config',{...header,kind:'config',policy}]]) {
  const raw=envelope(value,key); decode(raw,kind); await writeFile(path.join(output,`${kind}.json`),raw);
}
const checksumName = zipName+'.sha256';
await writeFile(path.join(output,checksumName),`${zipHash}  ${zipName}\n`);
await copyFile(zipPath,path.join(output,zipName)); await copyFile(installerPath,path.join(output,installerName));
const files=[];
for(const name of [zipName,checksumName,installerName]) files.push({name,sha256:await digest(path.join(output,name))});
await writeFile(path.join(output,'publish.json'),JSON.stringify({channel,version,sequence,files,stableApproved:channel==='stable',
  sourceCommit:sha,catalog:{channel,version,installer:installerName,size:(await stat(installerPath)).size,notes}})+'\n');
console.log(`Prepared ${output}; ZIP ${release.size} bytes; installer ${(await stat(installerPath)).size} bytes`);
if(mode === 'publish') {
  // Explicit publish is a separate last step after independent review. No auto
  // deployment of server code or Caddy changes is hidden inside a release.
  run('git',['fetch','origin','desktop/integration-263-claude-main']);
  run('git',['merge-base','--is-ancestor',sha,'origin/desktop/integration-263-claude-main']);
  const incoming=`/opt/bilipai-distribution/incoming/${channel}-${sequence}-${randomBytes(4).toString('hex')}`;
  run('ssh',['hermes','mkdir','-p',incoming]);
  run('scp',[...files.map(f=>path.join(output,f.name)),...['manifest.json','config.json','publish.json'].map(n=>path.join(output,n)),`hermes:${incoming}/`]);
  console.log(run('ssh',['hermes','python3','/opt/bilipai-distribution/publish.py','/opt/bilipai-distribution/data',incoming]));
}
console.log(`Withdrawal/rollback: node desktop/distribution/withdraw.mjs ${version} ${channel}`);
