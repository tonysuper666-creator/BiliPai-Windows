from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2]
def read(p):return p.read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
rows=[]
def transform(path,changes):
 s=read(REPO/path);base=s
 for before,after in changes:
  assert s.count(before)==1,(path,before[:100],s.count(before))
  s=s.replace(before,after,1)
  rows.append({'target':path,'before':before,'after':after,'beforeSha256LF':sha(before),'afterSha256LF':sha(after)})
 write(LANE/'review-only-native'/path,s)
 return {'target':path,'baseSha256LF':sha(base),'candidateSha256LF':sha(s),'hunks':len(changes)}
native=r'''// General video image payload; same Session / owner dispatcher / callback drain.
extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiSharePrepareMedia(wchar_t const* path,wchar_t const* title,wchar_t const* text,uint64_t* result) noexcept {
    if(!path||!title||!text||!result)return E_POINTER;
    *result=0;
    try {
        auto pathLength=wcsnlen_s(path,32761),titleLength=wcsnlen_s(title,257),textLength=wcsnlen_s(text,65537);
        if(pathLength==0||pathLength>=32761||titleLength==0||titleLength>256||textLength>65536)return E_INVALIDARG;
        apartment init(RO_INIT_MULTITHREADED);
        auto attributes=GetFileAttributesW(path);
        if(attributes==INVALID_FILE_ATTRIBUTES||(attributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)))return E_INVALIDARG;
        auto value=std::make_shared<Session>();
        value->file=StorageFile::GetFileFromPathAsync(path).get();
        auto size=value->file.GetBasicPropertiesAsync().get().Size();
        auto extension=value->file.FileType();
        if(size==0||size>32ULL*1024*1024 || (extension!=L".jpg"&&extension!=L".jpeg"&&extension!=L".png"&&extension!=L".webp"))return E_INVALIDARG;
        value->mediaFile=true;value->shareTitle=hstring(title);value->shareText=hstring(text);
        auto token=nextToken.fetch_add(1);
        std::lock_guard lock(registryGate);
        if(sessions.size()>=8)return HRESULT_FROM_WIN32(ERROR_TOO_MANY_OPEN_FILES);
        sessions.emplace(token,std::move(value));*result=token;return S_OK;
    }catch(...){return to_hresult();}
}

'''
receipts=[]
receipts.append(transform('desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp',[
 ('    bool textOnly=false;','    bool textOnly=false;\n    bool mediaFile=false;'),
 ('namespace bilipai_native_diagnostic_share_detail {\nHRESULT bindOnOwnerThread',native+'namespace bilipai_native_diagnostic_share_detail {\nHRESULT bindOnOwnerThread'),
 ('value->package.Properties().Title(value->textOnly ? value->shareTitle','value->package.Properties().Title((value->textOnly || value->mediaFile) ? value->shareTitle'),
 # The first replacement is one Title occurrence; Description replaced separately below.
 ('value->package.Properties().Description(value->textOnly ? value->shareTitle','value->package.Properties().Description((value->textOnly || value->mediaFile) ? value->shareTitle'),
 ('if(!value->textOnly)value->package.SetText(L"请查看附件中的日志文件");','if(value->mediaFile)value->package.SetText(value->shareText);\n                else if(!value->textOnly)value->package.SetText(L"请查看附件中的日志文件");'),
 ]))
path='desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt'
s=read(REPO/path)
share='    suspend fun share(title: String, text: String, stillOwned: () -> Boolean): Boolean = operations.withLock {'
new=r'''    suspend fun share(title:String,text:String,stillOwned:()->Boolean):Boolean =
        sharePrepared(title,text,stillOwned,null) { result -> api.BilipaiSharePrepareText(WString(title.ifBlank { "BiliPai 分享" }),WString(text),result) }

    /** The SAME serialized native actor retains the file grant until a real native
     * terminal event/revocation. false never claims receiver delivery. */
    suspend fun shareMedia(path:Path,title:String,text:String,stillOwned:()->Boolean,onRetired:(Boolean)->Unit):Boolean {
        var prepared=false
        var retired=false
        val retirement:(Boolean)->Unit={safe ->if(!retired){retired=true;onRetired(safe)}}
        try {
            val verified=UpdateStorage.existingPathWithoutLinks(path.toAbsolutePath())
            require(Files.isRegularFile(verified,LinkOption.NOFOLLOW_LINKS) && Files.size(verified) in 1..32L*1024*1024)
            require(verified.fileName.toString().substringAfterLast('.').lowercase() in setOf("jpg","jpeg","png","webp"))
            return sharePrepared(title,text,stillOwned,retirement) { result ->
                val hr=api.BilipaiSharePrepareMedia(WString(verified.toString()),WString(title.ifBlank {"BiliPai 分享"}),WString(text),result)
                prepared=result.value!=0L
                hr
            }
        } finally {if(!prepared)retirement(true)}
    }
    private suspend fun sharePrepared(title:String,text:String,stillOwned:()->Boolean,onRetired:((Boolean)->Unit)?,prepare:(LongByReference)->Int):Boolean = operations.withLock {'''
changes=[
 ('        fun BilipaiSharePrepareText(title: WString, text: WString, result: LongByReference): Int\n', '        fun BilipaiShareProbe():Int\n        fun BilipaiSharePrepareText(title: WString, text: WString, result: LongByReference): Int\n'),
 ('        fun BilipaiSharePrepareText(title: WString, text: WString, result: LongByReference): Int','        fun BilipaiSharePrepareText(title: WString, text: WString, result: LongByReference): Int\n        fun BilipaiSharePrepareMedia(path:WString,title:WString,text:WString,result:LongByReference):Int'),
 ('    private var active: Long? = null','    private var active: Long? = null\n    private var activeMediaRetirement:((Boolean)->Unit)? = null'),
 ('            check(api.BilipaiShareRetire(token, IntByReference(), IntByReference()) >= 0) { "原生分享尚未释放" }\n            active = null',
  '            val state=IntByReference(-1);val supplied=IntByReference(1)\n            check(api.BilipaiShareRetire(token,state,supplied) >= 0) { "原生分享尚未释放" }\n            active = null\n            val retirement=activeMediaRetirement;activeMediaRetirement=null\n            retirement?.invoke(state.value==3 || state.value==4 || supplied.value==0)'),
 (share,new),
 ('    fun retire() { closing.set(true) }','    fun probeMediaAvailable():Boolean = try { !closing.get() && api.BilipaiShareProbe() >= 0 } catch(_:Exception) {false} catch(_:LinkageError) {false}\n    fun retire() { closing.set(true) }'),
 ('            check(api.BilipaiSharePrepareText(WString(title.ifBlank { "BiliPai 分享" }), WString(text), result) >= 0 && result.value != 0L)','            check(prepare(result) >= 0 && result.value != 0L)'),
 ('        active = result.value','        active = result.value\n        activeMediaRetirement = onRetired'),
 ('        withContext(NonCancellable + Dispatchers.IO) {\n            check(prepare(result) >= 0 && result.value != 0L)\n        }\n        active = result.value\n        activeMediaRetirement = onRetired\n        try {',
  '        try {\n        withContext(NonCancellable + Dispatchers.IO) {\n            check(prepare(result) >= 0 && result.value != 0L)\n            active = result.value\n            activeMediaRetirement = onRetired\n        }'),
 ('    suspend fun shutdown() = withContext(NonCancellable) {',
  '    /** Explicit user cache clear on the same actor; it does not shut down future shares. */\n    suspend fun clearVideoShareFiles(clear:()->Unit) = operations.withLock {\n        currentCoroutineContext().ensureActive();check(!closing.get())\n        watcher?.cancel();watcher=null\n        closeActive()\n        withContext(Dispatchers.IO) { clear() }\n    }\n    suspend fun shutdown() = withContext(NonCancellable) {'),
]
receipts.append(transform(path,changes))
consent=r'''    /** Explicit original consent on the same serial writer. All three original
     * keys publish atomically; capture the caller Job before entering the actor. */
    suspend fun setOriginalCrashConsent(enabled:Boolean,stillOwned:()->Boolean,commit:((()->Unit)->Boolean)) {
        val caller=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        await(submit {
            caller?.ensureActive()
            if(!stillOwned())throw kotlinx.coroutines.CancellationException("诊断授权页面已退役")
            if(!commit {
                caller?.ensureActive()
                if(!stillOwned())throw kotlinx.coroutines.CancellationException("诊断授权页面已退役")
                store.update("settings",mapOf(
                    "crash_tracking_enabled" to kotlinx.serialization.json.JsonPrimitive(enabled),
                    "crash_tracking_consent_shown" to kotlinx.serialization.json.JsonPrimitive(true),
                    "enhanced_diagnostic_logging_enabled" to kotlinx.serialization.json.JsonPrimitive(enabled)))
                mutableEnhanced.value=enabled
                if(enabled)recordSessionStart()
                else {collector.clearRuntimeDiagnostics();deletePrivate(resolveRuntimeLogFile(root.toFile()).toPath())}
                mutableError.value=null
            })throw kotlinx.coroutines.CancellationException("诊断授权写入已退役")
            Unit
        })
    }

'''
# Existing diagnostics actor, not a second collector/store/queue.
receipts.append(transform('desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnostics.kt',[
 ('    store:DesktopPluginStore,','    private val store:DesktopPluginStore,'),
 ('    suspend fun setEnhancedEnabled(enabled:Boolean)',consent+'    suspend fun setEnhancedEnabled(enabled:Boolean)'),
 ('import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n'),
]))
write(LANE/'native-local-hunks.json',json.dumps({'nativeCompiled':False,'nativeDLLReplaced':False,'sameActorAndBridge':True,'rows':rows,'targets':receipts},ensure_ascii=False,indent=2))
print('native precise hunks',len(rows))
