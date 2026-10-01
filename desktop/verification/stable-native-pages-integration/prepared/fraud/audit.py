"""Reverse only declared platform mappings, retaining complete original tokens."""
from pathlib import Path
import hashlib,importlib.util,json,re,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists());BASE=MAIN.parent/'BiliPai-v023'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(n,p):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:100]);return s.replace(a,b,1)
def main():
 host=load('audit_existing_host',BASE/'desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(BASE);parser=media.parser_for(BASE)
 protocol=load('audit_existing_protocol',BASE/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 original=read(HERE/'original-source/app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt')
 generated=read(HERE/'generated/com/android/purebilibili/data/repository/DesktopOriginalCommentFraudProtocol.kt')
 inventory=json.loads(read(HERE/'source-inventory.json'));checks=[]
 def prove(ok,name,**fields):assert ok,name;checks.append(dict(name=name,status='PASS',**fields))
 for record in inventory['bodies']:
  name=record['name'];before=media.function(original,name,parser).strip();after=media.function(generated,name,parser).strip()
  prove(sha(before)==record['sha256BodyLF'],'original exact body pin '+name,startLine=record['startLine'],sha256BodyLF=sha(before))
  after=after.replace('catch (e: CancellationException) { throw e } catch (e: Exception) {','catch (e: Exception) {')
  # Remove the declared ownedCall wrapper without changing its typed API call.
  while True:
   mask=protocol.masked(after);match=re.search(r'ownedCall\s*\{',mask)
   if not match:break
   opening=mask.index('{',match.start());end=protocol.balanced(mask,opening,'{','}')
   after=after[:match.start()]+after[opening+1:end-1].strip()+after[end:]
  if name=='rawCurlGuest':
   after=one(after,'    ensureOwned()\n    val buvid = buvid3()','    val buvid = com.android.purebilibili.core.store.TokenManager.buvid3Cache')
   after=one(after,'        .header(FORCE_COOKIE_HEADER, if (!buvid.isNullOrBlank()) "buvid3=$buvid;" else "")\n','')
   after=one(after,'        awaitDesktopCommentFraudRaw(rawCalls.newCall(request), checkOwned)','''        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }''')
  if name=='checkCommentStatus':
   after=one(after,'        ensureOwned()\n        ensureVisitor()\n        ensureOwned()','        VideoRepository.ensureBuvid3()')
   after=one(after,'            delay(actualWait)\n            ensureOwned()','            delay(actualWait)')
  if name=='probeCommentPresenceBySeekRpid':
   after=one(after,'        val params = TreeMap<String, String>().apply {','        val (imgKey, subKey) = getWbiKeys()\n        val params = TreeMap<String, String>().apply {')
   after=one(after,'val signedParams = signParams(params).also { ensureOwned() }','val signedParams = WbiUtils.sign(params, imgKey, subKey)')
  expected=protocol.drop_logs(before)
  tokens_before=[x[0] for x in parser.kotlin_tokens(expected)]
  tokens_after=[x[0] for x in parser.kotlin_tokens(after)]
  prove(tokens_before==tokens_after,'complete original tokens after declared reverse '+name,tokenCount=len(tokens_before))
 for name in ['DEFAULT_WAIT_MS','IMAGE_EXTRA_WAIT_MS','DELETE_CONFIRM_RETRY_DELAY_MS']:
  a=re.search(r'private const val '+name+r' =[^\n]+',original).group()
  b=re.search(r'private const val '+name+r' =[^\n]+',generated).group()
  prove(a==b,'original constant unchanged '+name,value=a)
 for dep in inventory['externalOwnedDependencies']:
  value=read(HERE/'dependency-inputs'/dep['path'])
  prove(sha(value)==dep['sha256LF'],'BGM-owned compiler dependency exact '+dep['path'],doNotInstallFromThisLane=True)
 fragment=read(HERE/'operations-member.fragment.kt')
 proofops=read(HERE/'proof-only/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt')
 baseops=read(HERE/'base-inputs/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt')
 prove(proofops.replace(fragment,'',1)==baseops,'whole proof Ops contains solely appended member delta',baseSha256LF=sha(baseops),fragmentSha256LF=sha(fragment))
 with zipfile.ZipFile(safe(HERE/'compile-02/prepared-fraud-protocol.jar')) as z:own={n for n in z.namelist() if n.endswith('.class')}
 with zipfile.ZipFile(safe(MAIN/'desktop/.local/stable-product-snapshot-11/main-kotlin.jar')) as z:actual={n for n in z.namelist() if n.endswith('.class')}
 overlap=sorted(own&actual)
 prove(all(n.startswith('com/bilipai/desktop/data/DesktopDynamicCardOperations') for n in overlap),'only proof-owned Ops family overlaps actual stable11; new helper/transport/BGM imported dependencies are absent',overlap=overlap)
 equal=json.loads(read(HERE/'production-byte-equality.json'))
 prove(equal['status']=='PASS' and equal['sameGeneratedHelperBytes'] and equal['sameOperationsFragmentBytes'],'production producer emits compiled identical helper and binding',producerSha256Bytes=equal['producerSha256Bytes'])
 result=dict(status='PASS',checkCount=len(checks),checks=checks,originalSourceSha256LF=sha(original),preparedOnly=True,noMainEdits=True)
 safe(HERE/'source-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(dict(status='PASS',checks=len(checks),sha256Bytes=hashlib.sha256(safe(HERE/'source-audit.json').read_bytes()).hexdigest())))
if __name__=='__main__':main()
