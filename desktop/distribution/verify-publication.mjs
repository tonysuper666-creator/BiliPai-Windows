// Publication admission uses only the source-pinned public key. Uploaded metadata
// and process environment cannot supply a replacement trust anchor.
import { readFileSync } from 'node:fs';
import { createPublicKey, verify } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { decode, domain, PUBLIC_KEY } from './sign.mjs';

const requireValue = (condition, message) => { if (!condition) throw Error(message); };
const integer = value => Number.isSafeInteger(value) && value > 0;
const hash = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const version = value => typeof value === 'string' && /^\d+(?:\.\d+){1,3}$/.test(value);
function header(value, kind, expected, now) {
  requireValue(value.schema === 1 && value.kind === kind && ['dev','stable'].includes(value.channel), 'Invalid document header');
  requireValue(value.channel === expected.channel && value.sequence === expected.sequence && integer(value.sequence), 'Publication channel/sequence mismatch');
  requireValue(Number.isSafeInteger(value.issuedAt) && Number.isSafeInteger(value.expiresAt) && value.issuedAt <= now+300 &&
    value.expiresAt > now && value.expiresAt > value.issuedAt && value.expiresAt-value.issuedAt <= 7*86400, 'Expired or invalid signed document');
}

export function verifyPublication(rawManifest, rawConfig, metadata, {publicKey = PUBLIC_KEY, now = Math.floor(Date.now()/1000)} = {}) {
  requireValue(Buffer.byteLength(rawManifest) <= 32768 && Buffer.byteLength(rawConfig) <= 16384, 'Document too large');
  const manifest = decode(rawManifest,'manifest',publicKey), config = decode(rawConfig,'config',publicKey);
  header(manifest,'manifest',metadata,now); header(config,'config',metadata,now);
  requireValue(Number.isInteger(manifest.rolloutPercent) && manifest.rolloutPercent >= 0 && manifest.rolloutPercent <= 100, 'Invalid rollout');
  requireValue(Array.isArray(manifest.withdrawn) && manifest.withdrawn.length <= 128 &&
    manifest.withdrawn.every(version) && new Set(manifest.withdrawn).size === manifest.withdrawn.length, 'Invalid withdrawal list');
  requireValue(manifest.channel !== 'stable' || manifest.stableApproved === true, 'Stable approval must be signed');
  const release = manifest.release, installer = manifest.installer;
  requireValue(release && version(release.version) && release.version === metadata.version && integer(release.releaseId) && integer(release.assetId), 'Release identity mismatch');
  requireValue(/^BiliPai-Windows-[A-Za-z0-9_.+-]+\.zip$/.test(release.assetName) && integer(release.size) && release.size <= 512*1024*1024 && hash(release.sha256), 'Invalid signed ZIP');
  const url = new URL(release.downloadUrl);
  requireValue(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash &&
    url.pathname.endsWith('/'+release.assetName), 'Invalid signed ZIP URL');
  const publicObject = createPublicKey({key:Buffer.from(publicKey,'base64'),type:'spki',format:'der'});
  const signature = Buffer.from(release.archiveSignature,'base64');
  requireValue(signature.length === 64 && signature.toString('base64') === release.archiveSignature &&
    verify(null,domain('archive',Buffer.from(release.sha256,'ascii')),publicObject,signature), 'Invalid archive signature');
  requireValue(installer && /^[A-Za-z0-9_.+ -]+\.(?:msi|exe)$/.test(installer.name) && integer(installer.size) && hash(installer.sha256), 'Missing or invalid signed installer digest');
  const catalog = metadata.catalog;
  requireValue(catalog && catalog.channel === manifest.channel && catalog.version === release.version &&
    catalog.installer === installer.name && catalog.size === installer.size && typeof catalog.notes === 'string', 'Download catalog differs from signed installer');
  const policy = config.policy;
  requireValue(policy && Object.keys(policy).every(k=>['automaticUpdatesEnabled','checkIntervalHours','announcement'].includes(k)) &&
    typeof policy.automaticUpdatesEnabled === 'boolean' && Number.isInteger(policy.checkIntervalHours) && policy.checkIntervalHours >= 1 &&
    policy.checkIntervalHours <= 24 && typeof policy.announcement === 'string' && policy.announcement.length <= 1000 &&
    !/[\x00-\x09\x0b-\x1f]/.test(policy.announcement), 'Invalid remote policy');
  return {manifest, config, catalog:{...catalog,withdrawn:manifest.withdrawn.includes(release.version)}};
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 2) throw Error('Usage: verify-publication.mjs < publication-input.json');
  const input = JSON.parse(readFileSync(0,'utf8'));
  console.log(JSON.stringify(verifyPublication(input.manifest,input.config,input.metadata)));
}
