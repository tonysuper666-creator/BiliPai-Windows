"""Select original fraud protocol only; BGM owns status/policy/persistence.
The sole existing Ops guestWeb callFactory and original auth api are injected.
No parallel client, schema, store, WBI cache, record or account is emitted.
"""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,textwrap,sys
sys.dont_write_bytecode=True
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
PATH='app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:100]);return s.replace(a,b,1)
def generate(repo: Path, output: Path, standalone: bool = False):
 BASE=Path(repo);HERE=Path(output)
 protocol=load('pinned_fraud_source_loader',BASE/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 sources,identities=protocol.load_pinned_sources(BASE,[PATH]);source=sources[PATH]
 assert identities[0]['pinnedCommit']==COMMIT
 assert sha(source)=='1da1505d0ec3be32726ff5aacf462a1ee864be945283c7846b635cd6939cc9f4'
 host=load('fraud_host',BASE/'desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(BASE);parser=media.parser_for(BASE)
 # Its local helper is intentionally not imported; select/log-mask through the
 # same complete Kotlin token parser, not line-based body truncation.
 records=[]
 names=['rawCurlGuest','checkCommentStatus','checkReplyComment','checkRootComment','probeCommentPresenceBySeekRpid','findTargetRpid','confirmDeletedBySecondProbe']
 import re
 def drop_logs(s):
  while True:
   tokens=parser.kotlin_tokens(s);found=None
   for i,t in enumerate(tokens[:-4]):
    if t[0]=='Logger' and tokens[i+1][0]=='.' and tokens[i+2][0] in ('d','e','w','i') and tokens[i+3][0]=='(':
     found=i;break
   if found is None:return s
   i=found;start=s.rfind('\n',0,tokens[i][1])+1;assert not s[start:tokens[i][1]].strip()
   end=i+3;depth=0
   while end<len(tokens):
    depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
    if depth==0:break
    end+=1
   s=s[:start]+s[tokens[end][2]:]
 functions=[]
 for name in names:
  original=media.function(source,name,parser).strip()
  records.append(dict(name=name,source=PATH,sha256BodyLF=sha(original),startLine=source[:source.index(original.splitlines()[0].strip())].count('\n')+1))
  body=drop_logs(original)
  # Cancellation is a platform lifetime signal, not an UNKNOWN verdict.
  body=body.replace('catch (e: Exception) {','catch (e: CancellationException) { throw e } catch (e: Exception) {')
  if name=='rawCurlGuest':
   body=one(body,'    val buvid = com.android.purebilibili.core.store.TokenManager.buvid3Cache','    ensureOwned()\n    val buvid = buvid3()')
   body=one(body,'        .build()','        .header(FORCE_COOKIE_HEADER, if (!buvid.isNullOrBlank()) "buvid3=$buvid;" else "")\n        .build()')
   body=one(body,'''        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }''','''        awaitDesktopCommentFraudRaw(rawCalls.newCall(request), checkOwned)''')
  if name=='checkCommentStatus':
   body=one(body,'        VideoRepository.ensureBuvid3()','        ensureOwned()\n        ensureVisitor()\n        ensureOwned()')
   body=body.replace('            delay(actualWait)','            delay(actualWait)\n            ensureOwned()')
  if name=='probeCommentPresenceBySeekRpid':
   body=one(body,'        val (imgKey, subKey) = getWbiKeys()\n','')
   body=one(body,'val signedParams = WbiUtils.sign(params, imgKey, subKey)','val signedParams = signParams(params).also { ensureOwned() }')
  # All typed waits use the injected same auth API and same captured owner.
  tokens=parser.kotlin_tokens(body)
  calls=[]
  for i,t in enumerate(tokens[:-3]):
   if t[0] in ('api','apiClient') and tokens[i+1][0]=='.' and tokens[i+2][0] in ('getReplyList','getReplyReply') and tokens[i+3][0]=='(':
    end=i+3;depth=0
    while end<len(tokens):
     depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
     if depth==0:break
     end+=1
    calls.append((t[1],tokens[end][2]))
  for start,end in reversed(calls):body=body[:start]+'ownedCall { '+body[start:end]+' }'+body[end:]
  functions.append(textwrap.indent(body,'    '))
 tokens=parser.kotlin_tokens(source)
 starts=[i for i,t in enumerate(tokens[:-1]) if t[0]=='class' and tokens[i+1][0]=='CommentTargetMatch']
 assert len(starts)==1
 at=starts[0];end=at+2
 while tokens[end][0]!='(':end+=1
 depth=0
 while end<len(tokens):
  depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
  if depth==0:break
  end+=1
 begin=source.rfind('\n',0,tokens[at][1])+1
 declarations=textwrap.dedent(source[begin:tokens[end][2]])
 constants=[]
 for name in ['DEFAULT_WAIT_MS','IMAGE_EXTRA_WAIT_MS','DELETE_CONFIRM_RETRY_DELAY_MS']:
  matches=re.findall(r'(?m)^    private const val '+name+r' =[^\n]+',source);assert len(matches)==1
  constants.append(matches[0].strip())
 declarations+='\nprivate companion object {\n'+textwrap.indent('\n'.join(constants),'    ')+'\n}'
 prefix='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import okhttp3.Call
import java.util.TreeMap

/** Original type=1 fraud detection. BGM subject type=47 does not change this
 * original protocol; persistence and UI are owned by the original BGM owner. */
internal class DesktopOriginalCommentFraudProtocol(
    private val api: BilibiliApi,
    private val rawCalls: Call.Factory,
    private val buvid3: () -> String?,
    private val signParams: suspend (Map<String, String>) -> Map<String, String>,
    private val ensureVisitor: suspend () -> Unit,
    private val checkOwned: () -> Unit,
) {
    private suspend fun ensureOwned() { currentCoroutineContext().ensureActive(); checkOwned() }
    private suspend fun <T> ownedCall(block: suspend () -> T): T {
        ensureOwned()
        return try { block().also { ensureOwned() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ensureOwned(); throw failure }
    }
'''
 generated=prefix+textwrap.indent(declarations.strip(),'    ')+'\n\n'+'\n\n'.join(functions)+'\n}\n'
 write(HERE/'generated/com/android/purebilibili/data/repository/DesktopOriginalCommentFraudProtocol.kt','// OriginalSource: '+PATH+'\n// OriginalSHA256: '+sha(source)+'\n'+generated)
 fragment='''
    // Desktop original fraud protocol binding; BGM owns records/status/policy.
    private val originalCommentFraud = com.android.purebilibili.data.repository.DesktopOriginalCommentFraudProtocol(
        api, guestWeb.callFactory(),
        { assertOwned(); repository.authCookies()["buvid3"] },
        { params -> assertOwned(); repository.signWebParams(params).also { coroutineContext.ensureActive(); assertOwned() } },
        { assertOwned(); repository.ensureSession(); assertOwned() },
        ::assertOwned,
    )
    suspend fun checkCommentStatus(aid: Long, rpid: Long, rootId: Long = 0, hasPictures: Boolean = false,
        sentAtSeconds: Long = 0, waitMs: Long = -1): Result<com.android.purebilibili.data.model.CommentFraudStatus> =
        result { read { originalCommentFraud.checkCommentStatus(aid, rpid, rootId, hasPictures, sentAtSeconds, waitMs).getOrThrow() } }
'''
 result=dict(originalCommit=COMMIT,originalSource=PATH,originalSourceSha256LF=sha(source),bodies=records,
  soleMemberFragment=True,operationsMemberFragment=fragment,identities=identities,
  externalOwnedDependencies=['com.android.purebilibili.data.model.CommentFraudStatus','com.android.purebilibili.data.repository.CommentFraudDetectionPolicyKt'],
  declaredPlatformMappings=['existing guestWeb callFactory; force Cookie only buvid3','existing same Repository WBI+visitor owner','structured cancellation instead of UNKNOWN; typed async results guarded','account/content log removal'],
  originalLimitsPreserved=['type=1 throughout root/sub/auth','guest reply-page URL omits type','String.contains regex-looking literal for guest invisible','five-step ctime binary search','root retry2200ms; default5000ms/images15000ms'])
 write(HERE/'source-inventory.json',json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 return result
if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true');args=parser.parse_args()
 generate(Path(args.repo),Path(args.output),args.standalone)
