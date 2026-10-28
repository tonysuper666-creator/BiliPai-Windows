// Isolated test material. The ephemeral private key never leaves this process.
import { generateKeyPairSync } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { envelope, archiveSignature, sha256 } from './sign.mjs';
const options = JSON.parse(readFileSync(0,'utf8') || '{}');
const pair = generateKeyPairSync('ed25519');
const version = '0.2.427.201', channel = options.channel || 'dev', sequence = options.sequence || 1;
const zip = Buffer.from(options.zip || 'fixture only'), installerBytes = Buffer.from('fixture only');
const assetName = `BiliPai-Windows-${version}-x64.zip`, name = `BiliPai-Windows-${version}.msi`;
const now = Math.floor(Date.now()/1000);
const header = {schema:1,channel,sequence,issuedAt:now,expiresAt:now+3600};
const release = {version,releaseId:1,assetId:2,assetName,size:zip.length,
  downloadUrl:`https://learn.tonysuper666.com/bilipai/artifacts/${version}/${assetName}`,sha256:sha256(zip),
  archiveSignature:archiveSignature(sha256(zip),pair.privateKey)};
if(options.badArchiveSignature) release.archiveSignature = Buffer.alloc(64).toString('base64');
const installer = {name,size:installerBytes.length,sha256:sha256(installerBytes)};
const manifest = {...header,kind:'manifest',rolloutPercent:100,withdrawn:options.withdrawn ? [version] : [],release,installer,stableApproved:options.stableApproved === true};
const config = {...header,kind:'config',policy:{automaticUpdatesEnabled:true,checkIntervalHours:6,announcement:''}};
const metadata = {channel,version,sequence,files:[{name:assetName,sha256:release.sha256},{name,sha256:installer.sha256}],
  catalog:{channel,version,installer:name,size:installer.size,notes:'test fixture'}};
console.log(JSON.stringify({manifest:envelope(manifest,pair.privateKey),config:envelope(config,pair.privateKey),metadata,
  publicKey:pair.publicKey.export({type:'spki',format:'der'}).toString('base64')}));
