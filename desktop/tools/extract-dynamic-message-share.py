"""Original dynamic share-v2 protocol/UI; only desktop ownership/transport seams."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,re
BASE='app/src/main/java/com/android/purebilibili/'
DIRECT=[BASE+'core/network/grpc/ProtoWire.kt',BASE+'feature/message/InboxUserInfoResolver.kt']
PATHS=DIRECT+[BASE+'core/network/grpc/BiliGrpcClient.kt',BASE+'data/repository/MessageShareGrpcRepository.kt',BASE+'feature/message/InboxViewModel.kt',BASE+'feature/message/MessageUserInfoLoader.kt',BASE+'data/repository/MessageRepository.kt',BASE+'feature/dynamic/components/DynamicShareToMessageDialog.kt']
def read(repo,p):return(_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n','\n')
def inventory(repo):return[dict(path=p,mode='direct'if p in DIRECT else'policy-extract',features=['settings-dynamic-full-card-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest())for p in PATHS]
def load(repo,name,path):
 spec=importlib.util.spec_from_file_location(name,repo/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def generate(repo,out,standalone=False):
 host=load(repo,'dynamic_message_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 appearance=load(repo,'dynamic_message_decl','desktop/tools/extract-appearance-platform.py');files=[]
 def emit(p,body,name):files.append(host.write(out,p,read(repo,p),body,name))
 def fun(s,n):return media.function(s,n,parser)
 for p in DIRECT:
  if standalone:emit(p,read(repo,p),'DesktopOriginal'+Path(p).name)
 p=BASE+'feature/message/InboxViewModel.kt';s=read(repo,p)
 body=appearance.declarations(parser,s,['UserBasicInfo'])
 emit(p,'package com.android.purebilibili.feature.message\n'+body,'DesktopOriginalMessageUserBasicInfo.kt')
 p=BASE+'core/network/grpc/BiliGrpcClient.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.network.NetworkModule','import okhttp3.OkHttpClient')
 s=s.replace('import com.android.purebilibili.core.store.TokenManager','import com.bilipai.desktop.data.DesktopDynamicGrpcCalls')
 s=s.replace('import com.android.purebilibili.core.util.Logger','')
 s=s.replace('internal object BiliGrpcClient {','internal class DesktopDynamicBiliGrpcClient(private val client: OkHttpClient, private val cookies: () -> Map<String,String>, private val mid: () -> Long?, private val accessToken: () -> String?, private val stillOwned: () -> Boolean) {')
 s=s.replace('private const val ','private val ')
 s=s.replace('fun request(path: String, message: ByteArray): ByteArray','suspend fun request(path: String, message: ByteArray): ByteArray')
 s=s.replace('TokenManager.accessTokenCache','accessToken()').replace('TokenManager.sessDataCache','cookies()["SESSDATA"]').replace('TokenManager.csrfCache','cookies()["bili_jct"]').replace('TokenManager.midCache','mid()')
 s=s.replace('NetworkModule.okHttpClient.newCall(request).execute().use { response ->','return DesktopDynamicGrpcCalls.execute(client, request, stillOwned) { response ->')
 s=s.replace('val body = response.body.bytes()','val body = DesktopDynamicGrpcCalls.readBody(response.body)')
 s=s.replace('return ProtoWire.unframe(body)','ProtoWire.unframe(body)')
 s=re.sub(r'\s*Logger\.w\(\s*"BiliGrpc",\s*"gRPC request failed:[^\n]+\n\s*\)', '',s)
 old='    '+fun(s,'resolveBuvid').replace('\n','\n    ')
 s=host.substitute(s,old,'internal fun resolveBuvid(): String {\n        check(stillOwned()) { "Dynamic share owner retired" }\n        return cookies()["buvid3"]?.takeIf(String::isNotBlank) ?: error("Visitor session is not initialized")\n    }')
 emit(p,s,'DesktopDynamicBiliGrpcClient.kt')
 p=BASE+'data/repository/MessageShareGrpcRepository.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.network.grpc.BiliGrpcClient','')
 s=s.replace('import com.android.purebilibili.core.store.TokenManager','')
 s=s.replace('internal object MessageShareGrpcRepository {','internal class DesktopDynamicMessageShareRepository(private val request: suspend (String, ByteArray) -> ByteArray, private val mid: () -> Long?) {')
 s=s.replace('private const val ','private val ')
 s=s.replace('BiliGrpcClient.request','request').replace('TokenManager.midCache','mid()')
 # Kotlin function types do not have named parameters; values/body stay exact.
 s=s.replace('path = SHARE_LIST_PATH,','SHARE_LIST_PATH,').replace('message = ProtoWire.int32','ProtoWire.int32').replace('path = SEND_MESSAGE_PATH,','SEND_MESSAGE_PATH,').replace('message = buildDynamicShareSendRequest','buildDynamicShareSendRequest')
 emit(p,s,'DesktopDynamicMessageShareRepository.kt')
 p=BASE+'feature/message/MessageUserInfoLoader.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.network.BilibiliApi\nimport com.android.purebilibili.core.network.SpaceApi')
 s=s.replace('import com.android.purebilibili.core.network.WbiKeyManager','').replace('import com.android.purebilibili.core.network.WbiUtils','')
 s=s.replace('internal object MessageUserInfoLoader {','internal class DesktopDynamicMessageUserInfoLoader(private val api: BilibiliApi, private val spaceApi: SpaceApi, private val sign: suspend (Map<String,String>) -> Map<String,String>) {')
 s=s.replace('NetworkModule.api','api').replace('NetworkModule.spaceApi','spaceApi')
 begin=s.index('            val keys = WbiKeyManager');end=s.index('            val response =',begin)
 s=s[:begin]+'            val params = sign(mapOf("mid" to mid.toString()))\n'+s[end:]
 # Account/exception strings are omitted at the desktop logging boundary.
 s='\n'.join(l for l in s.splitlines()if'android.util.Log.w'not in l)+'\n'
 emit(p,s,'DesktopDynamicMessageUserInfoLoader.kt')
 p=BASE+'data/repository/MessageRepository.kt';s=read(repo,p);f=fun(s,'getSessions')
 f='\n'.join(l for l in f.splitlines()if'Logger.d('not in l and'android.util.Log.e('not in l)+'\n'
 begin=f.index('        // [Debug]');end=f.index('        if (response.code',begin);f=f[:begin]+f[end:]
 body='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.core.network.MessageApi\nimport com.android.purebilibili.data.model.response.*\nimport kotlinx.coroutines.*\ninternal class DesktopDynamicMessageSessions(private val api:MessageApi) {\n'+f+'\n}\n'
 emit(p,body,'DesktopDynamicMessageSessions.kt')
 p=BASE+'feature/dynamic/components/DynamicShareToMessageDialog.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.ui.AppModalBottomSheet','import com.bilipai.desktop.ui.DesktopDynamicTextSelectionSheet as AppModalBottomSheet')
 s='\n'.join(l for l in s.splitlines()if not any(l.startswith('import '+v)for v in ['com.android.purebilibili.data.repository.MessageRepository','com.android.purebilibili.data.repository.MessageShareGrpcRepository','com.android.purebilibili.feature.message.MessageUserInfoLoader']))+'\n'
 s=s.replace('    var sessions by remember(item.id_str)','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    var sessions by remember(item.id_str)',1)
 s=s.replace('MessageShareGrpcRepository.getShareTargets','platform.getShareTargets').replace('MessageRepository.getSessions','platform.getMessageSessions').replace('MessageUserInfoLoader.fetch','platform.fetchMessageUserInfo').replace('MessageShareGrpcRepository.sendDynamicShare','platform.sendDynamicShare')
 emit(p,s,'DesktopOriginalDynamicShareToMessageDialog.kt')
 return files
