import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';
export const PUBLIC_KEY = 'MCowBQYDK2VwAyEAvZASuZWE290s1FESqsVkQltjpbpZCcVDst34VjklIc8=';
export const domain = (kind, bytes) => Buffer.concat([Buffer.from(`BiliPai-Windows/${kind}/v1\n`),Buffer.from(bytes)]);
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export function envelope(value, key) {
  if (!['manifest','config'].includes(value.kind)) throw Error('Invalid signed document kind');
  const bytes = Buffer.from(JSON.stringify(value));
  return JSON.stringify({payload:bytes.toString('base64'),signature:sign(null,domain(value.kind,bytes),key).toString('base64')})+'\n';
}
export function decode(raw, kind, publicKey = PUBLIC_KEY) {
  const signed = JSON.parse(raw), bytes = Buffer.from(signed.payload,'base64');
  const signature = Buffer.from(signed.signature,'base64');
  if(bytes.toString('base64') !== signed.payload || signature.length !== 64 || signature.toString('base64') !== signed.signature)
    throw Error('Invalid signature encoding');
  const key = createPublicKey({key:Buffer.from(publicKey,'base64'),type:'spki',format:'der'});
  if(!verify(null,domain(kind,bytes),key,signature)) throw Error('Invalid signature');
  const value = JSON.parse(bytes);
  if(value.kind !== kind) throw Error('Wrong signed document kind');
  return value;
}
export async function privateKey(file) {
  const info = await stat(file);
  if(!info.isFile() || (info.mode & 0o077)) throw Error('Signing key must be a protected file (chmod 600)');
  const key = createPrivateKey(await readFile(file));
  if(key.asymmetricKeyType !== 'ed25519' || createPublicKey(key).export({type:'spki',format:'der'}).toString('base64') !== PUBLIC_KEY)
    throw Error('Signing key does not match the client trust anchor');
  return key;
}
export const archiveSignature = (digest,key) => sign(null,domain('archive',Buffer.from(digest,'ascii')),key).toString('base64');
